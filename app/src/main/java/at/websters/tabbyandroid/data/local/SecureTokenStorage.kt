package at.websters.tabbyandroid.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import at.websters.tabbyandroid.data.ssh.KNOWN_HOSTS_NAME
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Sync tokens, SSH passwords and key material live here, encrypted with
 * Android Keystore. SSH host-key pins live in the OpenSSH `known_hosts`
 * file (see [KNOWN_HOSTS_NAME]) which JSch enforces.
 *
 * Fail-closed: if encrypted storage is unavailable there is NO plaintext
 * fallback (a silent downgrade would leak secrets). All accessors throw with
 * a clear message instead.
 */
class SecureTokenStorage(appContext: Context) {
    private val app = appContext.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

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

    /** Removes a saved password (call on profile delete — no orphan secrets). */
    fun removeSshPassword(profileId: String) {
        prefs.edit().remove("ssh_pw_$profileId").apply()
    }

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
     * Forgets pinned SSH host keys by deleting the OpenSSH `known_hosts`
     * file that JSch enforces. Servers will ask to verify again on next
     * connect (TOFU). This is the only store that matters — an earlier
     * revision kept a parallel JSON record that nothing enforced.
     */
    fun clearHostKeys(): Boolean {
        return runCatching {
            java.io.File(app.filesDir, KNOWN_HOSTS_NAME).takeIf { it.exists() }?.delete() ?: true
        }.getOrDefault(false)
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

    /** Removes a remembered vault passphrase (call on account delete). */
    fun removeVaultPassphrase(accountId: String) {
        prefs.edit().remove("vault_pw_$accountId").apply()
    }

    /**
     * Command history + macros, encrypted like every other secret (commands
     * routinely embed passwords/tokens; see [isSensitiveCommand] pre-filter).
     */
    fun getCommandHistory(): List<CmdEntry> =
        prefs.getString("cmd_history_json", null)?.let { raw ->
            runCatching { json.decodeFromString(ListSerializer(CmdEntry.serializer()), raw) }
                .getOrDefault(emptyList())
        } ?: emptyList()

    fun saveCommandHistory(history: List<CmdEntry>) {
        prefs.edit()
            .putString("cmd_history_json", json.encodeToString(ListSerializer(CmdEntry.serializer()), history))
            .apply()
    }

    fun clearCommandHistory() {
        prefs.edit().remove("cmd_history_json").apply()
    }

    fun getMacros(): List<Macro> =
        prefs.getString("macros_json", null)?.let { raw ->
            runCatching { json.decodeFromString(ListSerializer(Macro.serializer()), raw) }
                .getOrDefault(emptyList())
        } ?: emptyList()

    fun saveMacros(macros: List<Macro>) {
        prefs.edit()
            .putString("macros_json", json.encodeToString(ListSerializer(Macro.serializer()), macros))
            .apply()
    }
}
