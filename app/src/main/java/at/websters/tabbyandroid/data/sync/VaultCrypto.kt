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
    fun parseStored(map: Map<*, *>): StoredVault? =
        when (val env = examineEnvelope(map)) {
            is VaultEnvelope.Valid -> env.stored
            else -> null
        }

    /**
     * Inspects the `vault:` block and explains WHY it is unusable, so the UI
     * can show an actionable message instead of a bare "Invalid vault".
     * Only field names and detected *types* ever enter messages — never
     * values (ciphertext/salts stay out of logs and screens).
     */
    sealed interface VaultEnvelope {
        data object Missing : VaultEnvelope
        data class Valid(val stored: StoredVault) : VaultEnvelope
        data class Corrupt(val message: String) : VaultEnvelope
    }

    fun examineEnvelope(map: Map<*, *>): VaultEnvelope = examineEnvelope(map, null)

    /**
     * Same as above, but with the raw YAML text so number-damaged scalars
     * can be RESCUED: when `keySalt:`/`iv:`/`contents:` loads as a Number,
     * the literal text on that line is very often still the intact value
     * (some writer saved the true hex unquoted and nothing re-saved it as a
     * float yet). A rescued value must fully validate per field, and the
     * subsequent decrypt is self-verifying — a wrong rescue simply fails to
     * decrypt like any wrong key material. Only genuinely destroyed text
     * (`.inf`, `1.2e+35`) stays Corrupt.
     */
    fun examineEnvelope(map: Map<*, *>?, rawYaml: String?): VaultEnvelope {
        val raw = map?.get("vault") ?: return VaultEnvelope.Missing
        if (raw !is Map<*, *>) {
            return VaultEnvelope.Corrupt(
                "Invalid vault: the 'vault' section is not a settings block " +
                    "(found ${typeName(raw)}). The server config looks damaged — restore it from a backup."
            )
        }
        val contents = usableText(raw["contents"], "contents", rawYaml)
            ?: return VaultEnvelope.Corrupt(fieldProblem("contents", raw["contents"]))
        val salt = usableText(raw["keySalt"], "keySalt", rawYaml)
            ?: return VaultEnvelope.Corrupt(fieldProblem("keySalt", raw["keySalt"]))
        val iv = usableText(raw["iv"], "iv", rawYaml)
            ?: return VaultEnvelope.Corrupt(fieldProblem("iv", raw["iv"]))
        val version = when (val v = raw["version"]) {
            is Number -> v.toInt()
            is String -> v.trim().toIntOrNull()
            else -> null
        } ?: return VaultEnvelope.Corrupt(
            "Invalid vault: 'version' is missing or not a number " +
                "(found ${typeName(raw["version"])}). The vault section is incomplete — restore it from a backup."
        )
        return VaultEnvelope.Valid(
            StoredVault(
                version = version,
                contentsB64 = contents,
                saltHex = salt,
                ivHex = iv,
            )
        )
    }

    /**
     * The usable text of an envelope field: strings pass through; a Number
     * is rescued from the raw YAML line when the literal text validates for
     * that field. Anything else is null (the caller diagnoses why).
     */
    private fun usableText(value: Any?, field: String, rawYaml: String?): String? {
        if (value is String) return value
        if (value is Number && rawYaml != null) {
            val rescued = rescueRawScalar(rawYaml, field)
            if (rescued != null && isPlausibleFieldText(field, rescued)) return rescued
        }
        return null
    }

    /**
     * The literal scalar text after `field:` on its (last, like SnakeYAML
     * duplicate handling) YAML line, or null. Comment suffixes are stripped.
     */
    fun rescueRawScalar(rawYaml: String, field: String): String? {
        val re = Regex("""(?m)^\s*${Regex.escape(field)}:\s*(\S+)(?:\s+\#.*)?\s*$""")
        return re.findAll(rawYaml).lastOrNull()?.groupValues?.getOrNull(1)
    }

    private fun isHex(s: String): Boolean =
        s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    fun isPlausibleBase64(s: String): Boolean {
        val clean = s.filterNot { it.isWhitespace() }
        return clean.isNotEmpty() && clean.length % 4 == 0 &&
            clean.all {
                it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' ||
                    it == '+' || it == '/' || it == '='
            }
    }

    /** Field-shaped validation for a rescued raw scalar. */
    private fun isPlausibleFieldText(field: String, s: String): Boolean = when (field) {
        // Salt feeds PBKDF2 (any even hex length works); desktop uses 8 bytes.
        "keySalt" -> s.length % 2 == 0 && s.length in 16..128 && isHex(s)
        // IV must be exactly 16 bytes for AES-256-CBC.
        "iv" -> s.length == 32 && isHex(s)
        "contents" -> isPlausibleBase64(s)
        else -> false
    }

    private fun typeName(v: Any?): String = v?.let { it::class.simpleName } ?: "nothing"

    /**
     * User-facing explanation for an unusable envelope field. Only called
     * when [usableText] found nothing usable — rescue included, so a Number
     * here means the raw line was NOT intact hex/base64 (e.g. `.inf`,
     * `1.2e+35`): genuinely destroyed, restore-from-backup territory.
     */
    private fun fieldProblem(field: String, value: Any?): String {
        if (value is String) {
            return "Invalid vault: '$field' is empty — the vault section " +
                "is incomplete. Restore the server config from a backup."
        }
        if (value == null) {
            return "Invalid vault: '$field' is missing — the vault section " +
                "is incomplete. Restore the server config from a backup."
        }
        if (value is Number) {
            return "Invalid vault: '$field' was saved as a number instead of " +
                "text — a YAML number conversion damaged the config and the " +
                "original value cannot be recomputed. Restore config.yaml " +
                "from a backup (desktop or a previous upload), then pull again."
        }
        return "Invalid vault: '$field' has an unexpected type " +
            "(${typeName(value)}) — the vault section is damaged. " +
            "Restore the server config from a backup."
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

    fun hexToBytes(hex: String): ByteArray {
        // Strip ALL whitespace, not just the ends: some writers wrap long
        // hex across lines, and wrapped-but-valid hex must still open.
        val clean = hex.filterNot { it.isWhitespace() }
        require(clean.length % 2 == 0 && clean.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            "Invalid hex"
        }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    fun base64ToBytes(b64: String): ByteArray {
        // Whitespace-tolerant (some writers wrap/fold long base64 across
        // lines) but otherwise strict: garbage must stay a FORMAT error
        // ("Invalid vault"), never a wrong-passphrase error.
        require(isPlausibleBase64(b64)) {
            "Invalid base64"
        }
        return try {
            java.util.Base64.getMimeDecoder().decode(b64.filterNot { it.isWhitespace() })
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid base64")
        }
    }

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
