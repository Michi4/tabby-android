package at.websters.tabbyandroid

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import at.websters.tabbyandroid.data.local.ProfileRepository
import at.websters.tabbyandroid.ui.TabbyApp
import at.websters.tabbyandroid.ui.state.ConnectionsViewModel
import at.websters.tabbyandroid.ui.state.SyncAccountsViewModel
import at.websters.tabbyandroid.ui.state.TerminalTabsViewModel
import at.websters.tabbyandroid.ui.theme.TabbyTheme
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Single-activity app. Edge-to-edge for Android 16 (RedMagic 10 Pro),
 * handles display cutout + gesture nav via Scaffold insets.
 * Screenshots/screen capture are blocked by default (SSH shows live secrets);
 * the user can allow them in Settings → Privacy.
 * FragmentActivity (not ComponentActivity) so screens can show biometric prompts.
 */
class MainActivity : FragmentActivity() {
    @Volatile private var latestAllowScreen = false

    /**
     * App-lock session flag. Survives rotation via the saved instance state
     * (rotation must NOT re-prompt); cleared whenever the app truly leaves
     * the foreground, so returning always prompts again.
     */
    private var unlocked = false

    private fun applyScreenCapture(allow: Boolean) {
        latestAllowScreen = allow
        if (allow) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // re-apply (the collector also drives this; resume covers edge cases
        // like the flag being re-set by the system while paused)
        applyScreenCapture(latestAllowScreen)
        maybeAppLock()
    }

    override fun onPause() {
        super.onPause()
        // backgrounding re-arms the lock — but a mere rotation must not
        if (!isChangingConfigurations) unlocked = false
    }

    /** Shows the system biometric/device-PIN gate when app lock is on. */
    private fun maybeAppLock() {
        lifecycleScope.launch {
            val locked = try {
                ProfileRepository(applicationContext).appLock.first()
            } catch (_: Exception) {
                false
            }
            if (!locked || unlocked) return@launch
            val canAuth = BiometricManager.from(this@MainActivity).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            // no biometrics/PIN enrolled: cannot gate — stay usable, the
            // Settings subtitle already warns; do not strand the user
            if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) return@launch
            val prompt = BiometricPrompt(
                this@MainActivity,
                ContextCompat.getMainExecutor(this@MainActivity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult
                    ) {
                        unlocked = true
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        // user backed out or failed: send to background instead
                        // of showing hosts; returning re-prompts via onResume
                        moveTaskToBack(true)
                    }
                },
            )
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Unlock Tabby")
                    .setSubtitle("Verify it's you to see your hosts")
                    .setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    )
                    .build()
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyScreenCapture(latestAllowScreen)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        unlocked = savedInstanceState?.getBoolean(KEY_UNLOCKED) ?: false
        enableEdgeToEdge()
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        lifecycleScope.launch {
            ProfileRepository(applicationContext).allowScreenCapture.collect { allow ->
                applyScreenCapture(allow)
            }
        }
        setContent {
            TabbyTheme {
                Surface(Modifier.fillMaxSize()) {
                    val connections: ConnectionsViewModel = viewModel()
                    val tabs: TerminalTabsViewModel = viewModel()
                    val accounts: SyncAccountsViewModel = viewModel()
                    TabbyApp(connections, tabs, accounts)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_UNLOCKED, unlocked)
    }

    companion object {
        private const val KEY_UNLOCKED = "app_unlocked"
    }
}
