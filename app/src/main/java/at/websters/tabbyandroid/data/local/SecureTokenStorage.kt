package at.websters.tabbyandroid.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Sync tokens + SSH key passphrases live here, encrypted with Android Keystore.
 * Falls back to plain private prefs only if Keystore is unavailable (e.g. some JVM unit tests).
 */
class SecureTokenStorage(appContext: Context) {
    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                "tabby_secrets",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (_: Exception) {
            appContext.getSharedPreferences("tabby_secrets_fallback", Context.MODE_PRIVATE)
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
}
