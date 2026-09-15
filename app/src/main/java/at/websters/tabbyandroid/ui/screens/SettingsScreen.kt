package at.websters.tabbyandroid.ui.screens

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import at.websters.tabbyandroid.BuildConfig
import at.websters.tabbyandroid.data.local.KeyLayout
import at.websters.tabbyandroid.data.local.UiPrefsDefaults
import at.websters.tabbyandroid.data.local.allKeyIds
import at.websters.tabbyandroid.data.local.defaultKeyRows
import at.websters.tabbyandroid.data.local.keyLabelFor
import at.websters.tabbyandroid.ui.state.ConnectionsViewModel
import at.websters.tabbyandroid.ui.state.SyncAccountsViewModel
import at.websters.tabbyandroid.ui.state.TerminalTabsViewModel
import at.websters.tabbyandroid.ui.state.UpdateViewModel

/**
 * Settings home: Privacy, Terminal defaults for new tabs, Sync servers,
 * About. Rows with switches toggle on tap anywhere (not just the knob).
 */
@Composable
fun SettingsScreen(
    accounts: SyncAccountsViewModel,
    connections: ConnectionsViewModel,
    tabs: TerminalTabsViewModel,
    update: UpdateViewModel = viewModel(),
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader("Privacy")
        PrivacyCard(accounts)
        SectionHeader("Terminal")
        TerminalPrefsCard(tabs)
        KeyLayoutCard(tabs)
        SectionHeader("Sync")
        SyncSection(accounts, connections)
        SectionHeader("Updates")
        UpdatesCard(update)
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
    val appLock by vm.appLock.collectAsState()
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
            SwitchRow(
                title = "Lock app",
                subtitle = if (appLock) "Biometrics / device PIN on launch"
                else "Anyone opening the app sees your hosts",
                checked = appLock,
                onChange = vm::setAppLock,
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
            SwitchRow(
                title = "Pinch to zoom",
                subtitle = "Two-finger pinch changes font size",
                checked = prefs.pinchZoom,
                onChange = tabsVm::setUiPinchZoom,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Scrollback", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Kept per tab, in session",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = {
                    val opts = UiPrefsDefaults.SCROLLBACK_OPTIONS
                    val i = opts.indexOf(prefs.scrollback)
                    tabsVm.setUiScrollback(opts[(i + 1) % opts.size])
                }) {
                    Text("${prefs.scrollback / 1000}k lines")
                }
            }
            SwitchRow(
                title = "Command suggestions",
                subtitle = "Ranked from commands you ran (encrypted)",
                checked = prefs.suggestions,
                onChange = tabsVm::setUiSuggestions,
            )
            TextButton(onClick = { tabsVm.clearHistory() }) {
                Text("Clear command history")
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Key rows", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Visible rows of your custom layout below",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = {
                    tabsVm.setUiKeyRows(if (prefs.keyRows <= 0) 4 else prefs.keyRows - 1)
                }) {
                    Text(
                        when (prefs.keyRows) {
                            4 -> "4 rows"
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
        var fontError by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showFontEdit = false },
            title = { Text("Font size (sp)") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = {
                        draft = it.filter { c -> c.isDigit() }.take(3)
                        fontError = false
                    },
                    label = { Text("Size in sp (1–256)") },
                    singleLine = true,
                    isError = fontError,
                    supportingText = { if (fontError) Text("Enter a number from 1 to 256") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = draft.toIntOrNull()
                    if (n == null) {
                        // don't silently swallow invalid input: explain, stay open
                        fontError = true
                    } else {
                        tabsVm.setUiFontSize(n)
                        showFontEdit = false
                    }
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

/**
 * Key-layout editor with a live preview. Every change writes straight to
 * prefs (sanitized), so the preview — the real row renderer — always shows
 * exactly what the terminal will show. Reorder with ‹ ›, remove with ×,
 * add unused keys via the picker, add/remove rows, drag the spacing slider.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyLayoutCard(tabsVm: TerminalTabsViewModel) {
    val prefs by tabsVm.uiPrefs.collectAsState()
    val layout = prefs.keyLayout
    var selRow by remember { mutableStateOf(0) }
    var showAddKey by remember { mutableStateOf(false) }
    val rowIdx = selRow.coerceIn(0, (layout.rows.size - 1).coerceAtLeast(0))
    val row = layout.rows.getOrNull(rowIdx).orEmpty()

    fun update(rows: List<List<String>> = layout.rows, spacing: Int = layout.spacingDp) {
        tabsVm.setUiKeyLayout(KeyLayout(rows, spacing))
    }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp).animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Key layout", style = MaterialTheme.typography.bodyMedium)
            // ---- live preview: the real renderer, inert ----
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    layout.rows.take(prefs.keyRows.coerceIn(0, 4)).forEach { ids ->
                        TerminalKeyRow(
                            ids = ids,
                            spacingDp = layout.spacingDp,
                            ctrlMode = ModMode.OFF,
                            altMode = ModMode.OFF,
                            altGrMode = ModMode.OFF,
                            onModifier = {},
                            onSend = {},
                            onSubmitReturn = {},
                        )
                    }
                }
            }
            // ---- row tabs ----
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                layout.rows.forEachIndexed { i, _ ->
                    FilterChip(
                        selected = i == rowIdx,
                        onClick = { selRow = i },
                        label = { Text("Row ${i + 1}") },
                    )
                }
            }
            // ---- keys of the selected row: move ‹ ›, remove × ----
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                row.forEachIndexed { j, id ->
                    val canRemove = layout.rows.size > 1 || row.size > 1
                    IconButton(
                        onClick = {
                            val mut = row.toMutableList()
                            val item = mut.removeAt(j)
                            mut.add((j - 1).coerceAtLeast(0), item)
                            update(rows = layout.rows.toMutableList().also { it[rowIdx] = mut })
                        },
                        enabled = j > 0,
                        modifier = Modifier.size(28.dp),
                    ) { Icon(Icons.Filled.ChevronLeft, "Move left") }
                    AssistChip(
                        onClick = {},
                        label = { Text(keyLabelFor(id)) },
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    update(rows = layout.rows.toMutableList().also {
                                        it[rowIdx] = row.filterIndexed { k, _ -> k != j }
                                    })
                                },
                                enabled = canRemove,
                                modifier = Modifier.size(24.dp),
                            ) { Icon(Icons.Filled.Close, "Remove", modifier = Modifier.size(14.dp)) }
                        },
                    )
                    IconButton(
                        onClick = {
                            val mut = row.toMutableList()
                            val item = mut.removeAt(j)
                            mut.add((j + 1).coerceAtMost(mut.size), item)
                            update(rows = layout.rows.toMutableList().also { it[rowIdx] = mut })
                        },
                        enabled = j < row.size - 1,
                        modifier = Modifier.size(28.dp),
                    ) { Icon(Icons.Filled.ChevronRight, "Move right") }
                }
            }
            // ---- add key (only unused ids are offered — no duplicates) ----
            val used = layout.rows.flatten().toSet()
            val avail = allKeyIds().filterNot { it in used }
            ExposedDropdownMenuBox(expanded = showAddKey, onExpandedChange = { showAddKey = it }) {
                OutlinedButton(
                    onClick = { if (avail.isNotEmpty()) showAddKey = !showAddKey },
                    enabled = avail.isNotEmpty(),
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true),
                ) {
                    Icon(Icons.Filled.Add, null)
                    Text(if (avail.isEmpty()) "All keys placed" else "Add key")
                }
                ExposedDropdownMenu(expanded = showAddKey, onDismissRequest = { showAddKey = false }) {
                    avail.forEach { id ->
                        DropdownMenuItem(
                            text = { Text(keyLabelFor(id)) },
                            onClick = {
                                update(rows = layout.rows.toMutableList().also {
                                    it[rowIdx] = row + id
                                })
                                showAddKey = false
                            },
                        )
                    }
                }
            }
            // ---- rows, spacing, reset ----
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { update(rows = layout.rows + listOf(listOf("enter"))) },
                    enabled = layout.rows.size < 4,
                ) { Text("Add row") }
                OutlinedButton(
                    onClick = {
                        update(rows = layout.rows.filterIndexed { i, _ -> i != rowIdx })
                        selRow = (rowIdx - 1).coerceAtLeast(0)
                    },
                    enabled = layout.rows.size > 1,
                ) { Text("Remove row") }
                TextButton(onClick = { update(rows = defaultKeyRows(), spacing = 4) }) {
                    Text("Reset")
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Key spacing", style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = layout.spacingDp.toFloat(),
                        onValueChange = { update(spacing = it.toInt()) },
                        valueRange = 0f..16f,
                        steps = 15,
                        modifier = Modifier.weight(1f, fill = false).padding(horizontal = 8.dp),
                    )
                    Text(
                        "${layout.spacingDp}dp",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * In-app updates from GitHub releases: auto-checks daily, manual check on
 * tap, one-tap download via DownloadManager + system installer prompt.
 */
@Composable
private fun UpdatesCard(vm: UpdateViewModel) {
    val state by vm.ui.collectAsState()
    val context = LocalContext.current

    // completion receiver while this screen is composed: finished download
    // jumps straight to the installer
    DisposableEffect(state) {
        val recv = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) ?: return
                if (vm.onDownloadComplete(id)) {
                    Toast.makeText(context, "Update downloaded — opening installer", Toast.LENGTH_LONG).show()
                }
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        androidx.core.content.ContextCompat.registerReceiver(
            context, recv, filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(recv) }
    }

    fun onUpdate(info: at.websters.tabbyandroid.data.update.ReleaseInfo) {
        when (vm.startDownload(info)) {
            is UpdateViewModel.DownloadStart.AlreadyHave -> {
                if (!vm.installApk()) {
                    Toast.makeText(
                        context,
                        "Allow installs, then tap Update again",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            is UpdateViewModel.DownloadStart.Started ->
                Toast.makeText(context, "Downloading update…", Toast.LENGTH_SHORT).show()
            is UpdateViewModel.DownloadStart.Unavailable ->
                Toast.makeText(context, "Couldn't start the download", Toast.LENGTH_LONG).show()
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp).animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when (val s = state) {
                is UpdateViewModel.Ui.Idle ->
                    Text(
                        "Version ${BuildConfig.VERSION_NAME} — checking happens automatically, or tap below.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                is UpdateViewModel.Ui.Checking -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = androidx.compose.ui.Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Checking for updates…", style = MaterialTheme.typography.bodyMedium)
                }
                is UpdateViewModel.Ui.UpToDate ->
                    Text(
                        "Up to date (v${BuildConfig.VERSION_NAME}).",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                is UpdateViewModel.Ui.Available -> {
                    Text(
                        "Update available: v${s.info.version}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (s.info.notes.isNotBlank()) {
                        Text(
                            s.info.notes.take(300),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 4,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                    Button(onClick = { onUpdate(s.info) }) { Text("Update") }
                }
                is UpdateViewModel.Ui.Failed ->
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                is UpdateViewModel.Ui.Downloading ->
                    Text(
                        "Downloading… progress is in the notification; the installer opens by itself.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { vm.check(manual = true) }) { Text("Check now") }
            }
        }
    }
}
