package at.websters.tabbyandroid

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
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
 * the user can allow them in Sync → Privacy.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        lifecycleScope.launch {
            ProfileRepository(applicationContext).allowScreenCapture.collect { allow ->
                if (allow) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.setFlags(
                        WindowManager.LayoutParams.FLAG_SECURE,
                        WindowManager.LayoutParams.FLAG_SECURE,
                    )
                }
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
