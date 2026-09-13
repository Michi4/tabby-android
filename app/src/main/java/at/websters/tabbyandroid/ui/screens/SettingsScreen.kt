package at.websters.tabbyandroid.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import at.websters.tabbyandroid.BuildConfig
import at.websters.tabbyandroid.ui.state.ConnectionsViewModel
import at.websters.tabbyandroid.ui.state.SyncAccountsViewModel
import at.websters.tabbyandroid.ui.state.TerminalTabsViewModel

/**
 * Settings home: Privacy (screenshot gate + host keys), Terminal defaults
 * for newly opened tabs, Sync servers (Tabby Web instances), About/legal.
 */
@Composable
fun SettingsScreen(
    accounts: SyncAccountsViewModel,
    connections: ConnectionsViewModel,
    tabs: TerminalTabsViewModel,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader("Privacy")
        PrivacyCard(accounts)
        SectionHeader("Terminal")
        TerminalPrefsCard(tabs)
        SectionHeader("Sync")
        SyncSection(accounts, connections)
        SectionHeader("About")
        AboutCard()
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun PrivacyCard(vm: SyncAccountsViewModel) {
    val allowScreen by vm.allowScreen.collectAsState()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Allow screenshots & screen sharing",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = allowScreen,
                    onCheckedChange = vm::setAllowScreen,
                )
            }
            Text(
                "Screenshots are blocked by default: terminal output routinely contains secrets.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { vm.forgetHostKeys() }) {
                Text("Forget saved host keys")
            }
        }
    }
}

@Composable
private fun TerminalPrefsCard(tabsVm: TerminalTabsViewModel) {
    val prefs by tabsVm.uiPrefs.collectAsState()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Defaults for newly opened tabs (open tabs keep their own toggles).",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Font size", style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { tabsVm.setUiFontSize(prefs.fontSize - 1) },
                        enabled = prefs.fontSize > 10,
                    ) { Text("−") }
                    Text(
                        "${prefs.fontSize}",
                        modifier = Modifier.padding(horizontal = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    OutlinedButton(
                        onClick = { tabsVm.setUiFontSize(prefs.fontSize + 1) },
                        enabled = prefs.fontSize < 20,
                    ) { Text("+") }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Follow output", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = prefs.follow,
                    onCheckedChange = tabsVm::setUiFollow,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Key rows", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = {
                    tabsVm.setUiKeyRows(if (prefs.keyRows <= 0) 3 else prefs.keyRows - 1)
                }) {
                    Text(
                        when (prefs.keyRows) {
                            3 -> "3 rows"
                            2 -> "2 rows"
                            1 -> "1 row"
                            else -> "Hidden"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutCard() {
    val uriHandler = LocalUriHandler.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Unofficial community client — not affiliated with the Tabby developers.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { uriHandler.openUri("https://github.com/Michi4/tabby-android") }) {
                Text("Source code (MIT)")
            }
        }
    }
}
