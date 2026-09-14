package at.websters.tabbyandroid.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import at.websters.tabbyandroid.ui.screens.ConnectionsScreen
import at.websters.tabbyandroid.ui.screens.SettingsScreen
import at.websters.tabbyandroid.ui.screens.TerminalScreen
import at.websters.tabbyandroid.ui.state.ConnectionsViewModel
import at.websters.tabbyandroid.ui.state.SshKeysViewModel
import at.websters.tabbyandroid.ui.state.UpdateViewModel
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
    keys: SshKeysViewModel = viewModel(),
    update: UpdateViewModel = viewModel(),
) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val prefs by tabs.uiPrefs.collectAsState()
    val fullscreenTerminal = prefs.fullscreen && route == Routes.TERMINAL

    // daily update check (silent unless something is new)
    LaunchedEffect(Unit) { update.check(manual = false) }

    // While the keyboard is open the bottom bar would sit behind it as dead
    // space, pushing terminal keys needlessly high — hide it (all routes gain
    // room; the bar returns the moment the keyboard closes). The system
    // gesture inset is dropped too while open (it hides behind the keyboard
    // as well), so key rows land flush above the keyboard.
    // Fullscreen terminal also hides the bar (only the screen remains).
    val imeOpen = WindowInsets.isImeVisible
    Scaffold(
        contentWindowInsets = if (imeOpen) WindowInsets.systemBars.only(WindowInsetsSides.Top)
            else ScaffoldDefaults.contentWindowInsets,
        bottomBar = {
            AnimatedVisibility(visible = !imeOpen && !fullscreenTerminal) {
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
        // swipe-style route changes: Hosts <-> Terminal <-> Settings slide
        // in tab order, with a fade so it stays smooth at 144Hz
        val order = mapOf(Routes.CONNECTIONS to 0, Routes.TERMINAL to 1, Routes.SETTINGS to 2)
        fun fwd(initial: String?, target: String?): Boolean =
            (order[target] ?: 0) >= (order[initial] ?: 0)
        NavHost(nav, startDestination = Routes.CONNECTIONS, Modifier.padding(pad)) {
            composable(
                Routes.CONNECTIONS,
                enterTransition = {
                    val d = if (fwd(initialState.destination.route, targetState.destination.route)) 1 else -1
                    slideInHorizontally(tween(250)) { it * d } + fadeIn(tween(250))
                },
                exitTransition = {
                    val d = if (fwd(initialState.destination.route, targetState.destination.route)) 1 else -1
                    slideOutHorizontally(tween(250)) { -it * d } + fadeOut(tween(250))
                },
                popEnterTransition = {
                    val d = if (fwd(initialState.destination.route, targetState.destination.route)) 1 else -1
                    slideInHorizontally(tween(250)) { it * d } + fadeIn(tween(250))
                },
                popExitTransition = {
                    val d = if (fwd(initialState.destination.route, targetState.destination.route)) 1 else -1
                    slideOutHorizontally(tween(250)) { -it * d } + fadeOut(tween(250))
                },
            ) {
                ConnectionsScreen(connections, tabs, onOpenTerminal = { nav.navigate(Routes.TERMINAL) })
            }
            composable(
                Routes.TERMINAL,
                enterTransition = {
                    val d = if (fwd(initialState.destination.route, targetState.destination.route)) 1 else -1
                    slideInHorizontally(tween(250)) { it * d } + fadeIn(tween(250))
                },
                exitTransition = {
                    val d = if (fwd(initialState.destination.route, targetState.destination.route)) 1 else -1
                    slideOutHorizontally(tween(250)) { -it * d } + fadeOut(tween(250))
                },
                popEnterTransition = {
                    val d = if (fwd(initialState.destination.route, targetState.destination.route)) 1 else -1
                    slideInHorizontally(tween(250)) { it * d } + fadeIn(tween(250))
                },
                popExitTransition = {
                    val d = if (fwd(initialState.destination.route, targetState.destination.route)) 1 else -1
                    slideOutHorizontally(tween(250)) { -it * d } + fadeOut(tween(250))
                },
            ) { TerminalScreen(tabs, connections, keys) }
            composable(
                Routes.SETTINGS,
                enterTransition = {
                    val d = if (fwd(initialState.destination.route, targetState.destination.route)) 1 else -1
                    slideInHorizontally(tween(250)) { it * d } + fadeIn(tween(250))
                },
                exitTransition = {
                    val d = if (fwd(initialState.destination.route, targetState.destination.route)) 1 else -1
                    slideOutHorizontally(tween(250)) { -it * d } + fadeOut(tween(250))
                },
                popEnterTransition = {
                    val d = if (fwd(initialState.destination.route, targetState.destination.route)) 1 else -1
                    slideInHorizontally(tween(250)) { it * d } + fadeIn(tween(250))
                },
                popExitTransition = {
                    val d = if (fwd(initialState.destination.route, targetState.destination.route)) 1 else -1
                    slideOutHorizontally(tween(250)) { -it * d } + fadeOut(tween(250))
                },
            ) { SettingsScreen(accounts, connections, tabs, update) }
        }
    }
}
