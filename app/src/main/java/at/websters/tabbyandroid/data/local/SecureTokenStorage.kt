package at.websters.tabbyandroid.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Sync tokens, SSH passwords, key material and host-key pins live here,
 * encrypted with Android Keystore.
 *
 * Fail-closed: if encrypted storage is unavailable there is NO plaintext
 * fallback (a silent downgrade would leak secrets). All accessors throw with
 * a clear message instead.
 */
class SecureTokenStorage(appContext: Context) {
    private val app = appContext.applicationContext

    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                app,
                "tabby_secrets",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            throw IllegalStateException(
                "Encrypted storage is unavailable on this device (Keystore error). " +
                    "Tabby stores SSH secrets and refuses to keep them in plaintext, " +
                    "so sync and saved passwords are disabled.",
                e,
            )
        }
    }

    fun putAccountToken(accountId: String, token: String) {
        prefs.edit().putString("sync_token_$accountId", token.trim()).apply()
    }

    fun getAccountToken(accountId: String): String =
        prefs.getString("sync_token_$accountId", "").orEmpty()

    fun removeAccountToken(accountId: String) {
        prefs.edit().remove("sync_token_$accountId").apply()
    }

    fun putSshPassword(profileId: String, password: String) {
        prefs.edit().putString("ssh_pw_$profileId", password).apply()
    }

    fun getSshPassword(profileId: String): String =
        prefs.getString("ssh_pw_$profileId", "").orEmpty()

    /** Private key PEM + optional passphrase, encrypted at rest. */
    fun putSshKey(keyId: String, pem: String, passphrase: String) {
        prefs.edit()
            .putString("sshkey_pem_$keyId", pem.trim())
            .putString("sshkey_pp_$keyId", passphrase)
            .apply()
    }

    fun getSshKey(keyId: String): Pair<String, String>? {
        val pem = prefs.getString("sshkey_pem_$keyId", "").orEmpty()
        if (pem.isBlank()) return null
        return pem to prefs.getString("sshkey_pp_$keyId", "").orEmpty()
    }

    fun removeSshKey(keyId: String) {
        prefs.edit().remove("sshkey_pem_$keyId").remove("sshkey_pp_$keyId").apply()
    }

    /**
     * Pinned SSH host keys (`host:port` -> `type:base64-sha256`), JSON in one
     * encrypted entry. Used to detect changed host keys (MITM) across restarts.
     */
    fun getHostKeys(): Map<String, String> {
        val raw = prefs.getString("hostkeys_json", "").orEmpty()
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(raw)
                .let { it as kotlinx.serialization.json.JsonObject }
                .entries.associate { (k, v) -> k to (v as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty() }
                .filterValues { it.isNotBlank() }
        }.getOrDefault(emptyMap())
    }

    fun putHostKey(host: String, port: Int, fingerprint: String) {
        val all = getHostKeys().toMutableMap()
        all["$host:$port"] = fingerprint
        val obj = kotlinx.serialization.json.buildJsonObject {
            all.forEach { (k, v) -> put(k, kotlinx.serialization.json.JsonPrimitive(v)) }
        }
        prefs.edit().putString("hostkeys_json", obj.toString()).apply()
    }

    fun clearHostKeys() {
        prefs.edit().remove("hostkeys_json").apply()
    }

    /** Vault passphrase, only when the user opts into remembering it. */
    fun putVaultPassphrase(accountId: String, passphrase: String) {
        if (passphrase.isBlank()) {
            prefs.edit().remove("vault_pw_$accountId").apply()
        } else {
            prefs.edit().putString("vault_pw_$accountId", passphrase).apply()
        }
    }

    fun getVaultPassphrase(accountId: String): String =
        prefs.getString("vault_pw_$accountId", "").orEmpty()
}
