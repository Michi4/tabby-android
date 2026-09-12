package at.websters.tabbyandroid

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import at.websters.tabbyandroid.ui.TabbyApp
import at.websters.tabbyandroid.ui.state.ConnectionsViewModel
import at.websters.tabbyandroid.ui.state.SyncAccountsViewModel
import at.websters.tabbyandroid.ui.state.TerminalTabsViewModel
import at.websters.tabbyandroid.ui.theme.TabbyTheme

/**
 * Single-activity app. Edge-to-edge for Android 16 (RedMagic 10 Pro),
 * handles display cutout + gesture nav via Scaffold insets.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TabbyTheme(dark = true) {
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
