package at.websters.tabbyandroid.data.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class VaultBadPassphraseException : Exception("Incorrect vault passphrase")
class VaultFormatException(message: String) : Exception(message)

/**
 * Tabby desktop vault crypto, reimplemented exactly per
 * `tabby-core/src/services/vault.service.ts` (MIT):
 * PBKDF2-HMAC-SHA512 x100000 (32-byte key) -> AES-256-CBC, PKCS7 padding.
 * - passphrase bytes: UTF-8 (Node `Buffer.from(passphrase)`)
 * - salt: 8 random bytes, stored hex; iv: 16 random bytes, stored hex
 * - payload: JSON `{"config": {...}, "secrets": [...]}` encrypted -> base64
 * - wrong passphrase surfaces as a padding/cipher error -> [VaultBadPassphraseException]
 */
object VaultCrypto {
    data class StoredVault(
        val version: Int,
        val contentsB64: String,
        val saltHex: String,
        val ivHex: String,
    ) {
        // Ciphertext is not secret, but keep logs clean by construction.
        override fun toString(): String =
            "StoredVault(version=$version, contentsB64=<redacted ${contentsB64.length} chars>)"
    }

    data class VaultContent(
        val config: Map<String, Any?>,
        val secrets: List<Any?>,
    ) {
        // DECRYPTED config + secrets — must never appear in logs.
        override fun toString(): String = "VaultContent(<redacted>)"
    }

    /** Extracts the stored vault from a remote config map (`{vault: {...}, ...}`). */
    fun parseStored(map: Map<*, *>): StoredVault? {
        val v = map["vault"] as? Map<*, *> ?: return null
        val contents = v["contents"] as? String ?: return null
        val salt = v["keySalt"] as? String ?: return null
        val iv = v["iv"] as? String ?: return null
        if (contents.isBlank() || salt.isBlank() || iv.isBlank()) return null
        return StoredVault(
            version = (v["version"] as? Number)?.toInt() ?: return null,
            contentsB64 = contents,
            saltHex = salt,
            ivHex = iv,
        )
    }

    fun decrypt(vault: StoredVault, passphrase: String): VaultContent {
        if (vault.version != 1) throw VaultFormatException("Unsupported vault format version ${vault.version}")
        require(passphrase.isNotEmpty()) { "Enter the vault passphrase" }
        // Envelope fields are validated BEFORE any crypto: corrupt hex/base64
        // means a damaged vault (format error), never a wrong passphrase.
        val salt = try {
            hexToBytes(vault.saltHex)
        } catch (_: IllegalArgumentException) {
            throw VaultFormatException("Invalid vault data")
        }
        val iv = try {
            hexToBytes(vault.ivHex)
        } catch (_: IllegalArgumentException) {
            throw VaultFormatException("Invalid vault data")
        }
        val contents = try {
            base64ToBytes(vault.contentsB64)
        } catch (_: IllegalArgumentException) {
            throw VaultFormatException("Invalid vault data")
        }
        try {
            val key = derive(passphrase, salt)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val plain = String(cipher.doFinal(contents), Charsets.UTF_8)
            // Post-decrypt garbage (bad padding already threw above; non-JSON
            // here) means the key was wrong — the common wrong-passphrase case.
            val json = kotlinx.serialization.json.Json.parseToJsonElement(plain).jsonObject
            val config = (json["config"] as? JsonObject)?.toPlainMap() ?: emptyMap()
            val secrets = (json["secrets"] as? JsonArray)?.toPlainList() ?: emptyList()
            return VaultContent(config, secrets)
        } catch (e: VaultFormatException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw VaultBadPassphraseException()
        } catch (_: Exception) {
            // wrong passphrase (bad padding) or otherwise undecryptable payload
            throw VaultBadPassphraseException()
        }
    }

    fun encrypt(config: Map<String, Any?>, secrets: List<Any?>, passphrase: String): StoredVault {
        require(passphrase.isNotEmpty()) { "Enter the vault passphrase" }
        val rnd = SecureRandom()
        // YAML-safe salts: the hex MUST contain one of a,b,c,d,f. A salt of
        // only [0-9e] (e.g. "6e75…") is dumped unquoted by SnakeYAML and read
        // back by Tabby desktop (js-yaml) as a float/Infinity, which the next
        // desktop save then persists as "keySalt: .inf" — permanently
        // destroying the vault. Retries are cheap (~0.8% rejected).
        var salt: ByteArray
        var iv: ByteArray
        var tries = 0
        do {
            salt = ByteArray(8).also { rnd.nextBytes(it) }
            iv = ByteArray(16).also { rnd.nextBytes(it) }
            if (++tries > 100) break // paranoia; never happens in practice
        } while (!isYamlSafeHex(salt) || !isYamlSafeHex(iv))
        val key = derive(passphrase, salt)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val payload = buildJsonObject {
            put("config", config.toJsonElement())
            put("secrets", secrets.toJsonElement())
        }.toString()
        val enc = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
        return StoredVault(
            version = 1,
            contentsB64 = bytesToBase64(enc),
            saltHex = bytesToHex(salt),
            ivHex = bytesToHex(iv),
        )
    }

    private fun derive(passphrase: String, salt: ByteArray): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, 100000, 256)
        try {
            return factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Hex is YAML-safe only with one of a,b,c,d,f ('e'-only still looks numeric). */
    fun isYamlSafeHex(bytes: ByteArray): Boolean =
        bytesToHex(bytes).any { it == 'a' || it == 'b' || it == 'c' || it == 'd' || it == 'f' }

    fun hexToBytes(hex: String): ByteArray {        val clean = hex.trim()
        require(clean.length % 2 == 0 && clean.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            "Invalid hex"
        }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    fun base64ToBytes(b64: String): ByteArray = java.util.Base64.getDecoder().decode(b64.trim())

    fun bytesToBase64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)
}

fun JsonObject.toPlainMap(): Map<String, Any?> =
    entries.associate { (k, v) -> k to v.toPlain() }

fun JsonArray.toPlainList(): List<Any?> = map { it.toPlain() }

fun JsonElement.toPlain(): Any? = when (this) {
    is JsonNull -> null
    is JsonObject -> toPlainMap()
    is JsonArray -> toPlainList()
    is JsonPrimitive -> when {
        isString -> content
        booleanOrNull != null -> booleanOrNull!!
        longOrNull != null -> longOrNull!!
        doubleOrNull != null -> doubleOrNull!!
        else -> content
    }
    else -> null
}

fun Map<String, Any?>.toJsonElement(): JsonElement = buildJsonObject {
    forEach { (k, v) -> put(k, v.toJsonElement()) }
}

fun List<Any?>.toJsonElement(): JsonElement = buildJsonArray {
    forEach { add(it.toJsonElement()) }
}

@Suppress("UNCHECKED_CAST")
fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is String -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Int -> JsonPrimitive(this.toLong())
    is Long -> JsonPrimitive(this)
    is Double -> JsonPrimitive(this)
    is Float -> JsonPrimitive(this.toDouble())
    is Map<*, *> -> (this as Map<String, Any?>).toJsonElement()
    is List<*> -> (this as List<Any?>).toJsonElement()
    else -> JsonPrimitive(toString())
}
