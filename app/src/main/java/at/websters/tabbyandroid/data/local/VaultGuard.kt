package at.websters.tabbyandroid.data.local

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore-guarded storage for the vault passphrase ("unlock with biometrics
 * or device PIN every time" mode).
 *
 * A per-app AES-256-GCM key requires user authentication for every use and
 * lives in AndroidKeyStore (never leaves the TEE/StrongBox). Sealed blobs
 * (base64 iv+ciphertext) are safe in plain prefs - only the Keystore key,
 * gated behind a fresh biometric/device-credential auth, can open them.
 *
 * Correct usage REQUIRES the decrypt cipher to be unlocked by a BiometricPrompt
 * CryptoObject (see ui.util.Biometrics) - a bare prompt without CryptoObject
 * does not authorize Keystore keys.
 */
object VaultGuard {
    const val ALIAS = "tabby_vault_guard"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    fun isAvailable(): Boolean {
        return try {
            ensureKey()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Returns true if a sealed blob exists for this account. */
    fun hasSealed(prefs: Map<String, String>, accountId: String): Boolean =
        prefs[accountId]?.isNotBlank() == true

    @Throws(Exception::class)
    fun seal(plaintext: String): String {
        ensureKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyEntry().secretKey)
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return android.util.Base64.encodeToString(iv + cipherText, android.util.Base64.NO_WRAP)
    }

    /** Builds a decrypt cipher for a CryptoObject prompt. Throws [UserNotAuthenticated] when auth is needed. */
    @Throws(Exception::class)
    fun decryptCipher(sealed: String): Cipher {
        ensureKey()
        val raw = android.util.Base64.decode(sealed, android.util.Base64.DEFAULT)
        require(raw.size > 12) { "Invalid sealed data" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            keyEntry().secretKey,
            GCMParameterSpec(128, raw, 0, 12),
        )
        return cipher
    }

    fun openWith(cipher: Cipher, sealed: String): String {
        val raw = android.util.Base64.decode(sealed, android.util.Base64.DEFAULT)
        return String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }

    private data class Entry(val secretKey: javax.crypto.SecretKey)

    private fun keyEntry(): Entry {
        ensureKey()
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
        ks.load(null)
        val key = ks.getKey(ALIAS, null) as? javax.crypto.SecretKey
            ?: throw IllegalStateException("Guard key missing")
        return Entry(key)
    }

    @Suppress("DEPRECATION")
    private fun ensureKey() {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
        ks.load(null)
        if (ks.containsAlias(ALIAS)) return
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .apply {
                if (Build.VERSION.SDK_INT >= 30) {
                    setUserAuthenticationParameters(
                        60,
                        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                    )
                } else {
                    setUserAuthenticationValidityDurationSeconds(60)
                }
            }
            .build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(spec)
            generateKey()
        }
        // sanity: alias must exist now
        ks.load(null)
        check(ks.containsAlias(ALIAS)) { "Could not create guard key (no secure lock screen?)" }
    }

    class UserNotAuthenticated(cause: Throwable) : Exception(
        "Biometric or device-PIN authentication required", cause
    )
}
