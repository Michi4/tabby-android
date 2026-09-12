package at.websters.tabbyandroid.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import at.websters.tabbyandroid.data.local.ProfileRepository
import at.websters.tabbyandroid.data.model.RemoteConfigMeta
import at.websters.tabbyandroid.data.model.SyncAccount
import at.websters.tabbyandroid.ui.state.ConnectionsViewModel
import at.websters.tabbyandroid.ui.state.SyncAccountsViewModel
import java.util.UUID

/**
 * Multiple Tabby Web instances, each with own host + token + chosen remote config.
 * Like desktop Tabby, the host field starts empty: sync needs your own instance.
 */
@Composable
fun SyncAccountsScreen(
    vm: SyncAccountsViewModel,
    connections: ConnectionsViewModel,
) {
    val accounts by vm.accounts.collectAsState()
    val busy by vm.busy.collectAsState()
    val msg by vm.message.collectAsState()
    val vaultLocked by at.websters.tabbyandroid.data.sync.VaultLocks.locked.collectAsState()
    val snack = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<SyncAccount?>(null) }
    var confirmUpload by remember { mutableStateOf<SyncAccount?>(null) }
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(msg) { msg?.let { snack.showSnackbar(it); vm.dismiss() } }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                editing = SyncAccount(
                    id = UUID.randomUUID().toString(),
                    name = if (accounts.isEmpty()) "Home" else "Server ${accounts.size + 1}",
                    hostUrl = "",
                )
            }) { Icon(Icons.Filled.Add, "Add sync server") }
        }
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Unofficial community client — not affiliated with the Tabby developers.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text("Config sync requires an instance of the Tabby Web service.")
                        TextButton(onClick = { uriHandler.openUri(ProfileRepository.SYNC_DOCS_URL) }) {
                            Text("Learn more")
                        }
                    }
                }
            }
            item {
                PrivacyCard(vm)
            }
            if (accounts.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("No sync servers", style = MaterialTheme.typography.titleMedium)
                            Text("Add your Tabby Web instance URL plus your sync token (desktop Tabby → Settings → Config sync shows the same token). Multiple servers supported.")
                        }
                    }
                }
            }
            items(accounts, key = { it.id }) { acc ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(acc.name, style = MaterialTheme.typography.titleMedium)
                            Row {
                                IconButton(onClick = { editing = acc }) { Icon(Icons.Filled.Edit, "Edit") }
                                IconButton(onClick = { vm.deleteAccount(acc) }) { Icon(Icons.Filled.Delete, "Delete") }
                            }
                        }
                        Text(acc.hostUrl, style = MaterialTheme.typography.bodySmall)
                        Text(
                            if (acc.selectedConfigId != null) "Config: ${acc.selectedConfigName ?: acc.selectedConfigId}"
                            else "No remote config selected",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (acc.lastError != null) Text(acc.lastError, color = MaterialTheme.colorScheme.error)
                        if (acc.id in vaultLocked) {
                            VaultUnlockRow(
                                onUnlock = { pw, remember -> connections.unlockVault(acc, pw, remember) },
                                enabled = !busy,
                            )
                        }
                        acc.lastSyncAtEpochMs?.let {
                            Text(
                                "Last synced ${ago(it)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { connections.syncAll() }, enabled = !busy) {
                                Icon(Icons.Filled.Download, null)
                                Text("Pull")
                            }
                            Button(
                                onClick = { confirmUpload = acc },
                                enabled = !busy && acc.selectedConfigId != null,
                            ) {
                                Icon(Icons.Filled.Upload, null)
                                Text("Upload")
                            }
                        }
                    }
                }
            }
        }
    }

    confirmUpload?.let { acc ->
        AlertDialog(
            onDismissRequest = { confirmUpload = null },
            title = { Text("Upload to server?") },
            text = {
                Text(
                    "Push this device's profiles to '${acc.selectedConfigName ?: acc.selectedConfigId}' on ${acc.hostUrl}?\n\n" +
                        "Server entries you don't manage are kept. There is no auto-upload - this happens only when you tap Upload."
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.upload(acc); confirmUpload = null }) { Text("Upload") }
            },
            dismissButton = { TextButton(onClick = { confirmUpload = null }) { Text("Cancel") } },
        )
    }

    editing?.let { acc ->
        var name by remember(acc.id) { mutableStateOf(acc.name) }
        var host by remember(acc.id) { mutableStateOf(acc.hostUrl) }
        var token by remember(acc.id) { mutableStateOf("") }
        var configs by remember { mutableStateOf<List<RemoteConfigMeta>>(emptyList()) }
        var picked by remember(acc.id) { mutableStateOf(acc.selectedConfigId) }
        var testError by remember(acc.id) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (accounts.any { it.id == acc.id }) "Edit sync server" else "Add sync server") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("Sync host") },
                        placeholder = { Text("https://tabby.example.com") },
                        supportingText = { Text("Your own Tabby Web instance (https required)") },
                        singleLine = true,
                    )
                    OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("Secret sync token") }, supportingText = { Text("Saved — leave blank to reuse") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    Button(onClick = {
                        testError = null
                        val effectiveToken = token.ifBlank { vm.getToken(acc.id) }
                        vm.testAndList(name, host, effectiveToken) { list, error ->
                            configs = list
                            testError = error
                            if (error == null && picked == null) {
                                picked = list.firstOrNull()?.id
                            }
                        }
                    }, enabled = !busy) { Text(if (busy) "Testing…" else "Test & list configs") }
                    if (busy) CircularProgressIndicator()
                    if (testError != null) {
                        Text(testError!!, color = MaterialTheme.colorScheme.error)
                    } else if (configs.isEmpty() && !busy) {
                        Text("Tap Test to load configs from your server.")
                    }
                    configs.forEach { c ->
                        val sel = picked == c.id
                        TextButton(onClick = { picked = c.id }) {
                            Text((if (sel) "✓ " else "") + "${c.name} (#${c.id})")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val selName = configs.find { it.id == picked }?.name ?: acc.selectedConfigName
                        vm.saveAccount(acc.copy(name = name.ifBlank { "Server" }, hostUrl = host.trim(), selectedConfigId = picked, selectedConfigName = selName), token)
                        editing = null
                    },
                    enabled = host.isNotBlank(),
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PrivacyCard(vm: SyncAccountsViewModel) {
    val allowScreen by vm.allowScreen.collectAsState()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Privacy", style = MaterialTheme.typography.titleMedium)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    "Allow screenshots & screen sharing",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                androidx.compose.material3.Switch(
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
private fun VaultUnlockRow(onUnlock: (passphrase: String, remember: Boolean) -> Unit, enabled: Boolean) {
    var passphrase by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(true) }
    var pwVisible by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "This config is vault-encrypted. Enter the vault passphrase to unlock it. " +
                "It is never logged and only sent to your own server inside the encrypted config.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = passphrase, onValueChange = { passphrase = it },
            label = { Text("Vault passphrase") }, singleLine = true,
            visualTransformation = if (pwVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { pwVisible = !pwVisible }) {
                    Icon(if (pwVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "Show")
                }
            },
        )
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(checked = remember, onCheckedChange = { remember = it })
            Text("Remember on this device", style = MaterialTheme.typography.bodyMedium)
        }
        Button(
            onClick = { onUnlock(passphrase, remember); passphrase = "" },
            enabled = enabled && passphrase.isNotBlank(),
        ) { Text("Unlock & pull") }
    }
}

private fun ago(epochMs: Long): String {    val s = ((System.currentTimeMillis() - epochMs) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60}m ago"
        s < 86400 -> "${s / 3600}h ago"
        else -> "${s / 86400}d ago"
    }
}
