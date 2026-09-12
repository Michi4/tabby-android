package at.websters.tabbyandroid.ui.util

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import at.websters.tabbyandroid.data.local.VaultGuard

/**
 * Biometric-or-device-PIN prompts bound to the Keystore guard key.
 *
 * Prompts always use a CryptoObject: without it the system prompt does NOT
 * authorize Keystore keys, and decryption would fail right after a
 * "successful" scan. Two-phase flow: try the cipher, and only on
 * UserNotAuthenticatedException show the prompt, then retry once.
 */
object Biometrics {
    private const val AUTH = androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
        androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** True when the device can do biometrics or device-PIN auth. */
    fun canGuard(activity: FragmentActivity): Boolean =
        BiometricManager.from(activity).canAuthenticate(AUTH) ==
            BiometricManager.BIOMETRIC_SUCCESS

    fun guardStatus(activity: FragmentActivity): String? {
        if (!VaultGuard.isAvailable()) {
            return "No secure lock screen — set a PIN or biometrics first"
        }
        return when (BiometricManager.from(activity).canAuthenticate(AUTH)) {
            BiometricManager.BIOMETRIC_SUCCESS -> null
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                "No biometrics or device PIN enrolled — enroll one in system settings"
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
                "This device has no biometric hardware and no device PIN"
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                "Biometric hardware unavailable right now"
            else -> "Biometric authentication unavailable"
        }
    }

    private fun promptInfo(title: String, subtitle: String): BiometricPrompt.PromptInfo =
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            // NOTE: no negative button — it crashes when DEVICE_CREDENTIAL is allowed
            .setAllowedAuthenticators(AUTH)
            .build()

    private fun prompt(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val bp = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onError(errString.toString())
                }

                override fun onAuthenticationFailed() {
                    // system shows "try again" itself; terminal state comes via onError
                }
            },
        )
        bp.authenticate(promptInfo(title, subtitle))
    }

    /**
     * Opens a guarded vault blob. ALWAYS prompts for biometrics/device PIN
     * first (never silently reuses an auth window), then decrypts.
     */
    fun unlockWithGuard(
        activity: FragmentActivity,
        title: String,
        sealed: String,
        onOk: (plain: String) -> Unit,
        onErr: (msg: String) -> Unit,
    ) {
        prompt(
            activity, title, "Verify it's you to unlock",
            onSuccess = {
                try {
                    val cipher = VaultGuard.decryptCipher(sealed)
                    onOk(VaultGuard.openWith(cipher, sealed))
                } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
                    onErr("Lock screen changed — guarded keys were wiped. Enroll again.")
                } catch (e: Exception) {
                    onErr(e.message ?: "Cannot open guarded storage")
                }
            },
            onError = onErr,
        )
    }

    /** Seals a fresh passphrase behind biometrics/device PIN (always prompts). */
    fun sealWithGuard(
        activity: FragmentActivity,
        title: String,
        plain: String,
        onOk: (sealed: String) -> Unit,
        onErr: (msg: String) -> Unit,
    ) {
        prompt(
            activity, title, "Verify it's you to protect this passphrase",
            onSuccess = {
                try {
                    onOk(VaultGuard.seal(plain))
                } catch (e: Exception) {
                    onErr(e.message ?: "Cannot seal guarded storage")
                }
            },
            onError = onErr,
        )
    }
}
