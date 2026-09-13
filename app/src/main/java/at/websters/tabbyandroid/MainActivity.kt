package at.websters.tabbyandroid

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyScreenCapture(latestAllowScreen)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
}
