package at.websters.tabbyandroid.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import at.websters.tabbyandroid.ui.screens.ConnectionsScreen
import at.websters.tabbyandroid.ui.screens.SyncAccountsScreen
import at.websters.tabbyandroid.ui.screens.TerminalScreen
import at.websters.tabbyandroid.ui.state.ConnectionsViewModel
import at.websters.tabbyandroid.ui.state.SyncAccountsViewModel
import at.websters.tabbyandroid.ui.state.TerminalTabsViewModel

object Routes {
    const val CONNECTIONS = "connections"
    const val TERMINAL = "terminal"
    const val SYNC = "sync"
}

@Composable
fun TabbyApp(
    connections: ConnectionsViewModel,
    tabs: TerminalTabsViewModel,
    accounts: SyncAccountsViewModel,
) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    Scaffold(
        bottomBar = {
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
                    selected = route == Routes.SYNC,
                    onClick = { nav.navigate(Routes.SYNC) { launchSingleTop = true } },
                    icon = { Icon(Icons.Filled.CloudSync, null) },
                    label = { Text("Sync") },
                )
            }
        }
    ) { pad ->
        NavHost(nav, startDestination = Routes.CONNECTIONS, Modifier.padding(pad)) {
            composable(Routes.CONNECTIONS) {
                ConnectionsScreen(connections, tabs, onOpenTerminal = { nav.navigate(Routes.TERMINAL) })
            }
            composable(Routes.TERMINAL) { TerminalScreen(tabs) }
            composable(Routes.SYNC) { SyncAccountsScreen(accounts, connections) }
        }
    }
}
