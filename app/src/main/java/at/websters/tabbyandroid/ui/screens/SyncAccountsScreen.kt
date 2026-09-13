package at.websters.tabbyandroid.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.fragment.app.FragmentActivity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
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
 * Sync servers section (Settings → Sync): multiple Tabby Web instances, each
 * with own host + token + chosen remote config. Like desktop Tabby, the host
 * field starts empty: sync needs your own instance.
 */
@Composable
fun SyncSection(
    vm: SyncAccountsViewModel,
    connections: ConnectionsViewModel,
) {
    val accounts by vm.accounts.collectAsState()
    val busy by vm.busy.collectAsState()
    val msg by vm.message.collectAsState()
    val forceAcc by vm.forceUpload.collectAsState()
    val vaultLocked by at.websters.tabbyandroid.data.sync.VaultLocks.locked.collectAsState()
    val snack = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<SyncAccount?>(null) }
    var confirmUpload by remember { mutableStateOf<SyncAccount?>(null) }
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(msg) { msg?.let { snack.showSnackbar(it); vm.dismiss() } }

    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Config sync requires an instance of the Tabby Web service.")
                    TextButton(onClick = { uriHandler.openUri(ProfileRepository.SYNC_DOCS_URL) }) {
                        Text("Learn more")
                    }
                }
            }
            Button(onClick = {
                editing = SyncAccount(
                    id = UUID.randomUUID().toString(),
                    name = if (accounts.isEmpty()) "Home" else "Server ${accounts.size + 1}",
                    hostUrl = "",
                )
            }) {
                Icon(Icons.Filled.Add, null)
                Text("Add sync server")
            }
            if (accounts.isEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("No sync servers", style = MaterialTheme.typography.titleMedium)
                        Text("Add your Tabby Web instance URL plus your sync token (desktop Tabby → Settings → Config sync shows the same token). Multiple servers supported.")
                    }
                }
            }
            accounts.forEach { acc ->
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
                                account = acc,
                                connections = connections,
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
        SnackbarHost(snack, modifier = Modifier.align(Alignment.BottomCenter))
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

    forceAcc?.let { acc ->
        AlertDialog(
            onDismissRequest = { vm.dismissForceUpload() },
            title = { Text("Server changed — overwrite?") },
            text = {
                Text(
                    "The config on ${acc.hostUrl} changed since your last pull " +
                        "(e.g. edited on desktop). Uploading now overwrites those " +
                        "server-side changes with this device's profiles.\n\n" +
                        "Pull first to merge, or upload anyway.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.upload(acc, force = true)
                    vm.dismissForceUpload()
                }) { Text("Upload anyway") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        connections.syncAll()
                        vm.dismissForceUpload()
                    }) { Text("Pull first") }
                    TextButton(onClick = { vm.dismissForceUpload() }) { Text("Cancel") }
                }
            },
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
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(
                                    selected = sel,
                                    onClick = { picked = c.id },
                                    role = Role.RadioButton,
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = sel, onClick = null)
                            Text(
                                "${c.name} (#${c.id})",
                                modifier = Modifier.padding(start = 8.dp),
                            )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultUnlockRow(
    account: SyncAccount,
    connections: ConnectionsViewModel,
    enabled: Boolean,
) {
    val modes by connections.vaultModes.collectAsState()
    val sealedMap by connections.vaultSealed.collectAsState()
    var passphrase by remember(account.id) { mutableStateOf("") }
    var mode by remember(account.id) {
        mutableStateOf(modes[account.id] ?: ProfileRepository.LOCK_SESSION)
    }
    var pwVisible by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    var bioError by remember { mutableStateOf<String?>(null) }
    val activity = LocalContext.current as? FragmentActivity
    val guardOk = remember(activity) {
        activity?.let { at.websters.tabbyandroid.ui.util.Biometrics.canGuard(it) } ?: false
    }
    val hasSealed = sealedMap[account.id]?.isNotBlank() == true

    fun modeLabel(m: String) = when (m) {
        ProfileRepository.LOCK_FOREVER -> "Remember forever"
        ProfileRepository.LOCK_GUARDED -> "Biometrics / device PIN"
        else -> "Ask every time"
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "This config is vault-encrypted. The passphrase is never logged and only " +
                "unlocks your own server copy in memory.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ExposedDropdownMenuBox(expanded = modeMenu, onExpandedChange = { modeMenu = it }) {
            OutlinedTextField(
                value = modeLabel(mode),
                onValueChange = {},
                readOnly = true,
                label = { Text("Unlock method") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modeMenu) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true).fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Ask every time (default)") },
                    onClick = { mode = ProfileRepository.LOCK_SESSION; modeMenu = false },
                )
                DropdownMenuItem(
                    text = { Text("Remember forever (encrypted, no prompts)") },
                    onClick = { mode = ProfileRepository.LOCK_FOREVER; modeMenu = false },
                )
                DropdownMenuItem(
                    text = { Text("Biometrics / device PIN (every unlock)") },
                    enabled = guardOk,
                    onClick = { mode = ProfileRepository.LOCK_GUARDED; modeMenu = false },
                )
            }
        }
        if (mode == ProfileRepository.LOCK_GUARDED && !guardOk) {
            Text(
                "Biometrics and device PIN are unavailable — enroll one in system settings first.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (mode != ProfileRepository.LOCK_GUARDED || !hasSealed) {
            OutlinedTextField(
                value = passphrase, onValueChange = { passphrase = it; bioError = null },
                label = { Text("Vault passphrase") }, singleLine = true,
                visualTransformation = if (pwVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { pwVisible = !pwVisible }) {
                        Icon(
                            if (pwVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            if (pwVisible) "Hide" else "Show",
                        )
                    }
                },
            )
        }
        if (bioError != null) {
            Text(bioError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (mode) {
                ProfileRepository.LOCK_GUARDED -> {
                    if (hasSealed) {
                        Button(
                            onClick = {
                                val act = activity
                                if (act == null) {
                                    bioError = "Cannot show biometric prompt here"
                                    return@Button
                                }
                                at.websters.tabbyandroid.ui.util.Biometrics.unlockWithGuard(
                                    act, "Unlock vault", sealedMap[account.id].orEmpty(),
                                    onOk = { connections.unlockVaultWithPlain(account, it) },
                                    onErr = { bioError = it },
                                )
                            },
                            enabled = enabled,
                        ) { Text("Unlock with biometrics") }
                    } else {
                        Button(
                            onClick = {
                                val act = activity
                                if (act == null) {
                                    bioError = "Cannot show biometric prompt here"
                                    return@Button
                                }
                                at.websters.tabbyandroid.ui.util.Biometrics.sealWithGuard(
                                    act, "Protect vault passphrase", passphrase,
                                    onOk = { blob ->
                                        connections.finishGuardedEnroll(account, blob, passphrase)
                                        passphrase = ""
                                    },
                                    onErr = { bioError = it },
                                )
                            },
                            enabled = enabled && passphrase.isNotBlank() && guardOk,
                        ) { Text("Protect with biometrics") }
                    }
                }
                else -> {
                    Button(
                        onClick = {
                            connections.unlockVault(account, passphrase, mode)
                            passphrase = ""
                        },
                        enabled = enabled && passphrase.isNotBlank(),
                    ) { Text("Unlock & pull") }
                }
            }
            TextButton(onClick = { connections.forgetVaultPassphrase(account) }) { Text("Forget") }
        }
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
