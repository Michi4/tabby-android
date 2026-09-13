package at.websters.tabbyandroid.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import at.websters.tabbyandroid.BuildConfig
import at.websters.tabbyandroid.ui.state.ConnectionsViewModel
import at.websters.tabbyandroid.ui.state.SyncAccountsViewModel
import at.websters.tabbyandroid.ui.state.TerminalTabsViewModel

/**
 * Settings home: Privacy, Terminal defaults for new tabs, Sync servers,
 * About. Rows with switches toggle on tap anywhere (not just the knob).
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

/** A full-width row whose switch toggles when tapping anywhere on the row. */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(checked, onValueChange = onChange, role = Role.Switch)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun PrivacyCard(vm: SyncAccountsViewModel) {
    val allowScreen by vm.allowScreen.collectAsState()
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp).animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            SwitchRow(
                title = "Screenshots & screen sharing",
                subtitle = if (allowScreen) "Allowed" else "Blocked (shows black)",
                checked = allowScreen,
                onChange = vm::setAllowScreen,
            )
            Text(
                "Terminal output often contains secrets — blocked by default.",
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
    var showFontEdit by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp).animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                "Defaults for new tabs.",
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
                    OutlinedButton(onClick = { tabsVm.setUiFontSize(prefs.fontSize - 1) }) { Text("−") }
                    TextButton(onClick = { showFontEdit = true }) {
                        Text("${prefs.fontSize}sp", style = MaterialTheme.typography.bodyLarge)
                    }
                    OutlinedButton(onClick = { tabsVm.setUiFontSize(prefs.fontSize + 1) }) { Text("+") }
                }
            }
            SwitchRow(
                title = "Follow output",
                subtitle = "Auto-scroll to newest output",
                checked = prefs.follow,
                onChange = tabsVm::setUiFollow,
            )
            SwitchRow(
                title = "Fullscreen",
                subtitle = "Screen only — tap for keyboard",
                checked = prefs.fullscreen,
                onChange = tabsVm::setUiFullscreen,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Key rows", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Row 1: Esc Tab Ctrl ←↑↓→ Alt AltGr",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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

    if (showFontEdit) {
        var draft by remember(prefs.fontSize) { mutableStateOf("${prefs.fontSize}") }
        AlertDialog(
            onDismissRequest = { showFontEdit = false },
            title = { Text("Font size (sp)") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.filter { c -> c.isDigit() }.take(3) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    draft.toIntOrNull()?.let { tabsVm.setUiFontSize(it) }
                    showFontEdit = false
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { showFontEdit = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AboutCard() {
    val uriHandler = LocalUriHandler.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Unofficial client — not affiliated with Tabby.",
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
