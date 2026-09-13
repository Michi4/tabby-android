package at.websters.tabbyandroid.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import at.websters.tabbyandroid.ui.state.SshKeysViewModel
import kotlinx.coroutines.launch

/**
 * Import / generate / delete local SSH private keys.
 * Keys never leave the device (never synced or uploaded).
 */
@Composable
fun KeysDialog(keysVm: SshKeysViewModel, onDismiss: () -> Unit) {
    val keys by keysVm.keys.collectAsState()
    val busy by keysVm.busy.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var name by remember { mutableStateOf("") }
    var pem by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var generatedPub by remember { mutableStateOf<String?>(null) }
    var pwVisible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SSH keys") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (keys.isEmpty()) {
                    Text(
                        "No keys yet. Import your existing private key, or generate " +
                            "a new one and add its public part to the server.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(keys, key = { it.id }) { k ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(k.name, modifier = Modifier.padding(vertical = 8.dp))
                                IconButton(onClick = { keysVm.deleteKey(k.id) }) {
                                    Icon(Icons.Filled.Delete, "Delete key")
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Key name") }, singleLine = true,
                )
                OutlinedTextField(
                    value = pem, onValueChange = { pem = it; error = null },
                    label = { Text("Paste private key (PEM)") },
                    minLines = 3, maxLines = 6,
                )
                OutlinedTextField(
                    value = passphrase, onValueChange = { passphrase = it },
                    label = { Text("Key passphrase (if any)") }, singleLine = true,
                    visualTransformation = if (pwVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { pwVisible = !pwVisible }) {
                            Icon(
                                if (pwVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                if (pwVisible) "Hide passphrase" else "Show passphrase",
                            )
                        }
                    },
                )
                if (error != null) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                }
                generatedPub?.let { pub ->
                    OutlinedTextField(
                        value = pub, onValueChange = {},
                        readOnly = true, label = { Text("Public key — add to the server") },
                        minLines = 2, maxLines = 4,
                        trailingIcon = {
                            IconButton(onClick = { clipboard.setText(AnnotatedString(pub)) }) {
                                Icon(Icons.Filled.ContentCopy, "Copy public key")
                            }
                        },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(vertical = 12.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                keysVm.importKey(name, pem, passphrase).fold(
                                    onSuccess = { name = ""; pem = ""; passphrase = ""; error = null },
                                    onFailure = { error = it.message },
                                )
                            }
                        },
                        enabled = !busy && pem.isNotBlank(),
                    ) { Text("Import") }
                    Button(
                        onClick = {
                            scope.launch {
                                keysVm.generateKey(name.ifBlank { "phone-key" }).fold(
                                    onSuccess = { (_, pub) ->
                                        generatedPub = pub
                                        name = ""
                                        error = null
                                    },
                                    onFailure = { error = it.message },
                                )
                            }
                        },
                        enabled = !busy,
                    ) {
                        Icon(Icons.Filled.Add, null)
                        Text("Generate RSA key")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
