package at.websters.tabbyandroid.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import at.websters.tabbyandroid.data.model.Pin
import at.websters.tabbyandroid.data.model.SshProfile
import at.websters.tabbyandroid.data.sync.FolderNode
import at.websters.tabbyandroid.data.sync.allProfileIds
import at.websters.tabbyandroid.data.sync.buildForest
import at.websters.tabbyandroid.data.sync.findNode
import at.websters.tabbyandroid.ui.state.ConnectionsViewModel
import at.websters.tabbyandroid.ui.state.SshKeysViewModel
import at.websters.tabbyandroid.ui.state.TerminalTabsViewModel
import at.websters.tabbyandroid.ui.theme.parseHexColor
import java.util.UUID
import kotlinx.coroutines.launch

private const val PINS_KEY = "__pins"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConnectionsScreen(
    vm: ConnectionsViewModel,
    tabsVm: TerminalTabsViewModel,
    onOpenTerminal: () -> Unit,
    keysVm: SshKeysViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val pins by vm.pins.collectAsState()
    val collapsed by vm.collapsed.collectAsState()
    val allGroups by vm.groups.collectAsState()
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showAdd by remember { mutableStateOf(false) }
    var showAuth by remember { mutableStateOf<SshProfile?>(null) }
    var actionsFor by remember { mutableStateOf<SshProfile?>(null) }
    var pendingDelete by remember { mutableStateOf<SshProfile?>(null) }

    LaunchedEffect(state.syncMessage) {
        state.syncMessage?.let { snack.showSnackbar(it); vm.dismissMessage() }
    }

    val byId = remember(state.profiles) { state.profiles.associateBy { it.id } }
    val forest = remember(state.profiles, allGroups) { buildForest(state.profiles, allGroups) }
    val pinnedHostIds = remember(pins) {
        pins.filter { it.kind == Pin.HOST }.map { it.ref }.toSet()
    }
    val pinnedGroupKeys = remember(pins) {
        pins.filter { it.kind == Pin.GROUP }.map { it.ref }.toSet()
    }
    val pinnedHosts = remember(pins, byId) {
        pins.filter { it.kind == Pin.HOST }.mapNotNull { byId[it.ref] }
    }
    val pinnedNodes = remember(pins, forest) {
        pins.filter { it.kind == Pin.GROUP }.mapNotNull { findNode(forest.roots, it.ref) }
    }
    // everything hidden from the main list because a pinned folder covers it
    val hiddenByPins = remember(pinnedNodes) {
        pinnedNodes.flatMap { node ->
            buildList {
                add(node.key)
                addAll(node.allProfileIds())
                fun kids(n: FolderNode) {
                    n.children.forEach { add(it.key); kids(it) }
                }
                kids(node)
            }
        }.toSet()
    }
    val ungrouped = remember(forest, pinnedHostIds) {
        forest.ungrouped.filterNot { it.id in pinnedHostIds }
    }
    fun isPinnedHost(id: String) = id in pinnedHostIds

    // ---- recursive renderer (local LazyListScope extensions) ----
    fun LazyListScope.hostItem(p: SshProfile, pinned: Boolean, depth: Int, keyPrefix: String) {
        item(key = "$keyPrefix:${p.id}") {
            HostCard(
                p = p,
                pinned = pinned,
                depth = depth,
                onConnect = { showAuth = p },
                onLongPress = { actionsFor = p },
                onMoveUp = { vm.movePin(p.id, Pin.HOST, -1) },
                onMoveDown = { vm.movePin(p.id, Pin.HOST, 1) },
            )
        }
    }

    fun LazyListScope.folder(
        node: FolderNode,
        forceExpand: Boolean,
        pinControls: PinControls?,
        prune: Set<String>,
    ) {
        val isCollapsed = !forceExpand && node.key in collapsed
        stickyHeader(key = "f:${if (forceExpand) "pin:" else ""}${node.key}") {
            SectionHeader(
                title = node.name,
                count = "${node.totalProfiles}",
                collapsed = isCollapsed,
                onToggle = { vm.toggleCollapse(node.key) },
                depth = node.depth,
                pinActive = node.key in pinnedGroupKeys,
                onPin = { vm.togglePinGroup(node.key) },
                pinControls = pinControls,
            )
        }
        if (!isCollapsed) {
            node.ownProfiles
                .filterNot { it.id in pinnedHostIds }
                .forEach { hostItem(it, pinned = forceExpand && pinControls == null, depth = node.depth + 1, keyPrefix = "fp:${node.key}") }
            node.children
                .filterNot { it.key in prune }
                .forEach { folder(it, forceExpand, pinControls = null, prune) }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) { Icon(Icons.Filled.Add, "Add host") }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (state.profiles.isEmpty()) {
                    item {
                        ElevatedCard(Modifier.fillMaxWidth().padding(12.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("No hosts yet", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Pull SSH profiles from your Tabby Web instance (Sync tab), " +
                                        "tap + for a manual host, or type user@host:port below.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                // ---------- pinned ----------
                if (pins.isNotEmpty()) {
                    stickyHeader(key = PINS_KEY) {
                        SectionHeader(
                            icon = { Icon(Icons.Filled.PushPin, null, tint = MaterialTheme.colorScheme.tertiary) },
                            title = "Pinned",
                            count = "${pinnedHosts.size + pinnedNodes.sumOf { it.totalProfiles }}",
                            collapsed = PINS_KEY in collapsed,
                            onToggle = { vm.toggleCollapse(PINS_KEY) },
                        )
                    }
                    if (PINS_KEY !in collapsed) {
                        pinnedHosts.forEach { hostItem(it, pinned = true, depth = 0, keyPrefix = "pin") }
                        pinnedNodes.forEach { node ->
                            folder(
                                node = node,
                                forceExpand = true,
                                pinControls = PinControls(
                                    onMoveUp = { vm.movePin(node.key, Pin.GROUP, -1) },
                                    onMoveDown = { vm.movePin(node.key, Pin.GROUP, 1) },
                                    onUnpin = { vm.togglePinGroup(node.key) },
                                ),
                                prune = emptySet(),
                            )
                        }
                    }
                }
                // ---------- main forest ----------
                forest.roots
                    .filterNot { it.key in hiddenByPins || it.key in pinnedGroupKeys }
                    .forEach { folder(it, forceExpand = false, pinControls = null, prune = hiddenByPins) }
                ungrouped.forEach { hostItem(it, pinned = false, depth = 0, keyPrefix = "u") }
            }
            if (looksLikeHost(state.query)) {
                AssistChip(
                    onClick = {
                        val p = vm.quickConnect(state.query)
                        SessionPasswords.put(p.id, "")
                        tabsVm.open(p)
                        onOpenTerminal()
                    },
                    label = { Text("Connect to ${state.query.trim()}") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null) },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            // thumb-friendly bottom search + sync (end-padded so the FAB never covers it)
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 92.dp, top = 6.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = vm::setQuery,
                    label = { Text("Search ${state.profiles.size} hosts…") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    trailingIcon = {
                        if (state.query.isNotBlank()) {
                            IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Filled.Clear, "Clear") }
                        }
                    },
                )
                IconButton(onClick = { vm.syncAll() }, enabled = !state.syncing) {
                    if (state.syncing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.Refresh, "Pull from sync servers")
                }
            }
        }
    }

    // long-press actions: pin/unpin + delete
    actionsFor?.let { p ->
        HostActionsDialog(
            title = p.name,
            pinLabel = if (isPinnedHost(p.id)) "Unpin from top" else "Pin to top",
            onPin = { vm.togglePinHost(p); actionsFor = null },
            onDelete = { pendingDelete = p; actionsFor = null },
            onDismiss = { actionsFor = null },
        )
    }
    pendingDelete?.let { p ->
        val synced = p.origin != "manual"
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { vm.deleteProfile(p) }
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
            title = { Text("Delete host?") },
            text = {
                Text(
                    if (synced) "Remove '${p.name}' from this device? It will also be removed from the server on the next Upload."
                    else "Remove '${p.name}' from this device?"
                )
            },
        )
    }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        var host by remember { mutableStateOf("") }
        var port by remember { mutableStateOf("22") }
        var user by remember { mutableStateOf("root") }
        var password by remember { mutableStateOf("") }
        var pwVisible by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add host") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Host") }, singleLine = true)
                    OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Port") }, singleLine = true)
                    OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("User") }, singleLine = true)
                    OutlinedTextField(
                        value = password, onValueChange = { password = it },
                        label = { Text("Password (stored encrypted)") },
                        singleLine = true,
                        visualTransformation = if (pwVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { pwVisible = !pwVisible }) {
                                Icon(if (pwVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "Show password")
                            }
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (host.isBlank()) return@TextButton
                        val profile = SshProfile(
                            id = "manual:${UUID.randomUUID()}",
                            name = name.ifBlank { host.trim() },
                            host = host.trim(),
                            port = port.toIntOrNull()?.coerceIn(1, 65535) ?: 22,
                            username = user.ifBlank { "root" },
                        )
                        scope.launch {
                            vm.saveManualProfile(profile, password)
                            showAdd = false
                        }
                    },
                    enabled = host.isNotBlank(),
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } },
        )
    }

    showAuth?.let { p ->
        ConnectDialog(
            profile = p,
            vm = vm,
            keysVm = keysVm,
            onConnect = { updated, password, keyPem, keyPassphrase ->
                if (keyPem != null) SessionKeys.put(updated.id, keyPem to keyPassphrase)
                SessionPasswords.put(updated.id, password)
                tabsVm.open(updated)
                showAuth = null
                onOpenTerminal()
            },
            onDismiss = { showAuth = null },
        )
    }
}

private data class PinControls(
    val onMoveUp: () -> Unit,
    val onMoveDown: () -> Unit,
    val onUnpin: () -> Unit,
)

@Composable
private fun SectionHeader(
    title: String,
    count: String,
    collapsed: Boolean,
    onToggle: () -> Unit,
    depth: Int = 0,
    icon: @Composable (() -> Unit)? = null,
    pinActive: Boolean = false,
    onPin: (() -> Unit)? = null,
    pinControls: PinControls? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = (8 + depth * 14).dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(Modifier.padding(end = 4.dp)) { icon() }
        }
        IconButton(onClick = onToggle, modifier = Modifier.size(32.dp)) {
            Icon(
                if (collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                if (collapsed) "Expand" else "Collapse",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            count,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (pinControls != null) {
            IconButton(onClick = pinControls.onMoveUp, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.KeyboardArrowUp, "Move up")
            }
            IconButton(onClick = pinControls.onMoveDown, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.KeyboardArrowDown, "Move down")
            }
            TextButton(onClick = pinControls.onUnpin) { Text("Unpin") }
        } else if (onPin != null) {
            IconButton(onClick = onPin, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.PushPin,
                    if (pinActive) "Unpin folder" else "Pin folder",
                    tint = if (pinActive) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HostCard(
    p: SshProfile,
    pinned: Boolean,
    onConnect: () -> Unit,
    onLongPress: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    depth: Int = 0,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = (12 + depth * 14).dp)
            .combinedClickable(onClick = onConnect, onLongClick = onLongPress),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.size(12.dp).clip(CircleShape)
                    .background(parseHexColor(p.color) ?: MaterialTheme.colorScheme.primary),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    p.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    p.label(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (p.keyId != null) {
                Icon(Icons.Filled.Key, "Key auth", tint = MaterialTheme.colorScheme.tertiary)
            }
            if (pinned) {
                IconButton(onClick = onMoveUp, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.KeyboardArrowUp, "Move up")
                }
                IconButton(onClick = onMoveDown, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.KeyboardArrowDown, "Move down")
                }
            }
            IconButton(onClick = onConnect) {
                Icon(Icons.Filled.PlayArrow, "Connect", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun HostActionsDialog(
    title: String,
    pinLabel: String,
    onPin: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onPin, modifier = Modifier.fillMaxWidth()) {
                    Text(pinLabel, modifier = Modifier.fillMaxWidth())
                }
                TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete", color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectDialog(
    profile: SshProfile,
    vm: ConnectionsViewModel,
    keysVm: SshKeysViewModel,
    onConnect: (updated: SshProfile, password: String, keyPem: String?, keyPassphrase: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val keys by keysVm.keys.collectAsState()
    var password by remember(profile.id) { mutableStateOf("") }
    var pwVisible by remember { mutableStateOf(false) }
    var keyId by remember(profile.id) { mutableStateOf(profile.keyId) }
    var showKeys by remember { mutableStateOf(false) }
    var keyMenu by remember { mutableStateOf(false) }
    val showKeyPicker = profile.authType == "publicKey" || keys.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium)
                Text(profile.label(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (showKeyPicker) {
                    ExposedDropdownMenuBox(
                        expanded = keyMenu,
                        onExpandedChange = { keyMenu = it },
                    ) {
                        OutlinedTextField(
                            value = keys.find { it.id == keyId }?.name ?: "Password only",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("SSH key") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(keyMenu) },
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true).fillMaxWidth(),
                        )
                        ExposedDropdownMenu(expanded = keyMenu, onDismissRequest = { keyMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Password only") },
                                onClick = { keyId = null; keyMenu = false },
                            )
                            keys.forEach { k ->
                                DropdownMenuItem(
                                    text = { Text(k.name) },
                                    onClick = { keyId = k.id; keyMenu = false },
                                )
                            }
                        }
                    }
                    TextButton(onClick = { showKeys = true }) {
                        Icon(Icons.Filled.Key, null)
                        Text("Manage keys")
                    }
                }
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text(if (keyId != null) "Key passphrase (if any)" else "Password") },
                    supportingText = { Text("Saved — leave blank to reuse") },
                    singleLine = true,
                    visualTransformation = if (pwVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { pwVisible = !pwVisible }) {
                            Icon(if (pwVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "Show password")
                        }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val updated = if (keyId != profile.keyId) profile.copy(keyId = keyId) else profile
                scope.launch {
                    vm.savePassword(updated.id, password)
                    if (updated.origin == "manual") {
                        vm.saveManualProfile(updated, "")
                    } else if (keyId != profile.keyId) {
                        vm.updateSyncedProfile(updated)
                    }
                    // blank = reuse the stored secret (never prefilled into UI state)
                    val effectivePw = password.ifBlank { vm.getPassword(updated.id) }
                    val key = keyId?.let { keysVm.loadKey(it) }
                    onConnect(updated, effectivePw, key?.first, key?.second.orEmpty())
                }
            }) { Text("Connect") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (showKeys) {
        KeysDialog(keysVm = keysVm, onDismiss = { showKeys = false })
    }
}

private fun looksLikeHost(q: String): Boolean {
    val t = q.trim()
    if (t.length < 3 || t.contains(" ")) return false
    return t.contains(".") || t.contains(":") || t.contains("@")
}

/** Ephemeral in-memory passwords for the connect step (never logged, cleared on tab close). */
object SessionPasswords {
    private val map = mutableMapOf<String, String>()
    @Synchronized fun put(id: String, pw: String) { map[id] = pw }
    @Synchronized fun take(id: String): String = map.remove(id).orEmpty()
}

/** Ephemeral in-memory private-key material (PEM + passphrase), same lifecycle as passwords. */
object SessionKeys {
    private val map = mutableMapOf<String, Pair<String, String>>()
    @Synchronized fun put(id: String, key: Pair<String, String>) { map[id] = key }
    @Synchronized fun take(id: String): Pair<String, String>? = map.remove(id)
}
