package at.websters.tabbyandroid.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import at.websters.tabbyandroid.ui.screens.ConnectionsScreen
import at.websters.tabbyandroid.ui.screens.SettingsScreen
import at.websters.tabbyandroid.ui.screens.TerminalScreen
import at.websters.tabbyandroid.ui.state.ConnectionsViewModel
import at.websters.tabbyandroid.ui.state.SyncAccountsViewModel
import at.websters.tabbyandroid.ui.state.TerminalTabsViewModel

object Routes {
    const val CONNECTIONS = "connections"
    const val TERMINAL = "terminal"
    const val SETTINGS = "settings"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TabbyApp(
    connections: ConnectionsViewModel,
    tabs: TerminalTabsViewModel,
    accounts: SyncAccountsViewModel,
) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    // While the keyboard is open the bottom bar would sit behind it as dead
    // space, pushing terminal keys needlessly high — hide it (all routes gain
    // room; the bar returns the moment the keyboard closes). The system
    // gesture inset is dropped too while open (it hides behind the keyboard
    // as well), so key rows land flush above the keyboard.
    val imeOpen = WindowInsets.isImeVisible
    Scaffold(
        contentWindowInsets = if (imeOpen) WindowInsets.systemBars.only(WindowInsetsSides.Top)
            else ScaffoldDefaults.contentWindowInsets,
        bottomBar = {
            AnimatedVisibility(visible = !imeOpen) {
            NavigationBar {
                NavigationBarItem(
                    selected = route == Routes.CONNECTIONS,
                    onClick = { nav.navigate(Routes.CONNECTIONS) { launchSingleTop = true } },
                    icon = { Icon(Icons.Filled.Dns, null) },
                    label = { Text("Hosts") },
                )
                NavigationBarItem(
                    selected = route == Routes.TERMINAL,
                    onClick = { nav.navigate(Routes.TERMINAL) { launchSingleTop = true } },
                    icon = { Icon(Icons.Filled.Terminal, null) },
                    label = { Text("Terminal") },
                )
                NavigationBarItem(
                    selected = route == Routes.SETTINGS,
                    onClick = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                    icon = { Icon(Icons.Filled.Settings, null) },
                    label = { Text("Settings") },
                )
            }
            }
        }
    ) { pad ->
        NavHost(nav, startDestination = Routes.CONNECTIONS, Modifier.padding(pad)) {
            composable(Routes.CONNECTIONS) {
                ConnectionsScreen(connections, tabs, onOpenTerminal = { nav.navigate(Routes.TERMINAL) })
            }
            composable(Routes.TERMINAL) { TerminalScreen(tabs) }
            composable(Routes.SETTINGS) { SettingsScreen(accounts, connections, tabs) }
        }
    }
}
