package at.websters.tabbyandroid.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.websters.tabbyandroid.data.ssh.CtrlKeys
import at.websters.tabbyandroid.data.ssh.SshState
import at.websters.tabbyandroid.data.ssh.TerminalBuffer
import at.websters.tabbyandroid.data.ssh.diffEdit
import at.websters.tabbyandroid.ui.state.TerminalTabsViewModel
import at.websters.tabbyandroid.ui.theme.statusColor
import at.websters.tabbyandroid.ui.theme.termColor
import kotlinx.coroutines.launch

internal const val ESC = "\u001B"

/**
 * Termius-like terminal: browser-style tabs, tap-to-type screen (system
 * keyboard writes straight into SSH), sticky CTRL/ALT toggles, collapsible
 * extended keys, long-press line to copy. Tuned for tall 144Hz panels
 * (RedMagic 10 Pro): version-gated snapshots.
 */
@Composable
fun TerminalScreen(tabsVm: TerminalTabsViewModel) {
    val tabs by tabsVm.tabs.collectAsState()
    val activeId by tabsVm.active.collectAsState()
    var showQuick by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        // ---- slim header: no wasted space, bottom nav already says where we are ----
        val active = tabs.find { it.id == activeId }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    val i = tabs.indexOfFirst { it.id == activeId }
                    if (tabs.isNotEmpty()) tabsVm.select(tabs[(i - 1 + tabs.size) % tabs.size].id)
                },
                enabled = tabs.size > 1,
            ) { Icon(Icons.Filled.ChevronLeft, "Previous tab") }
            Column(Modifier.weight(1f)) {
                Text(
                    active?.profile?.name ?: "Terminal",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (active != null) {
                    val status by active.conn.status.collectAsState()
                    Text(
                        listOf(active.profile.label(), status)
                            .filter { it.isNotBlank() }
                            .joinToString(" • "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(
                onClick = {
                    val i = tabs.indexOfFirst { it.id == activeId }
                    if (tabs.isNotEmpty()) tabsVm.select(tabs[(i + 1) % tabs.size].id)
                },
                enabled = tabs.size > 1,
            ) { Icon(Icons.Filled.ChevronRight, "Next tab") }
            IconButton(onClick = { showQuick = true }) { Icon(Icons.Filled.Add, "New tab") }
        }

        // ---- tab strip ----
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (tabs.isEmpty()) {
                Text(
                    "No tabs — open a host from Hosts or tap +",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                )
            }
            tabs.forEach { t ->
                val st by t.conn.state.collectAsState()
                val selected = t.id == activeId
                Row(
                    Modifier.height(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .clickable { tabsVm.select(t.id) }
                        .padding(start = 10.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("●", color = statusColor(st), fontSize = 10.sp)
                    Text(
                        t.profile.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                    IconButton(
                        onClick = { tabsVm.close(t.id) },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(Icons.Filled.Close, "Close tab", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        val current = tabs.find { it.id == activeId } ?: tabs.lastOrNull()
        if (current == null) {
            Card(Modifier.padding(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No terminal tabs", style = MaterialTheme.typography.titleMedium)
                    Text("Open a host from Hosts, tap + at the top right, or start here:")
                    Button(onClick = { showQuick = true }) { Text("Quick connect") }
                }
            }
        } else {
            TerminalTabBody(tab = current, modifier = Modifier.weight(1f))
        }
    }

    if (showQuick) {
        var quick by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showQuick = false },
            title = { Text("Quick connect") },
            text = {
                OutlinedTextField(
                    value = quick, onValueChange = { quick = it },
                    label = { Text("user@host:port") }, singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (quick.isNotBlank()) {
                            tabsVm.openQuick(quick)
                            showQuick = false
                        }
                    },
                    enabled = quick.isNotBlank(),
                ) { Text("Connect") }
            },
            dismissButton = { TextButton(onClick = { showQuick = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TerminalTabBody(tab: TerminalTabsViewModel.Tab, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val state by tab.conn.state.collectAsState()
    val connStatus by tab.conn.status.collectAsState()
    val version by tab.conn.buffer.updates.collectAsState()
    var showHostKey by remember { mutableStateOf<String?>(null) }
    var follow by remember(tab.id) { mutableStateOf(true) }
    var fontSize by remember { mutableIntStateOf(13) }
    var pwVisible by remember { mutableStateOf(false) }
    var ctrl by remember(tab.id) { mutableStateOf(false) }
    var alt by remember(tab.id) { mutableStateOf(false) }
    var keysOpen by remember(tab.id) { mutableStateOf(true) }
    var localEcho by remember(tab.id) { mutableStateOf(false) }
    var password by remember(tab.id) { mutableStateOf(SessionPasswords.take(tab.profile.id)) }
    val scroll = rememberScrollState()

    val snapshot = remember(version, tab.id) { tab.conn.buffer.snapshot() }
    LaunchedEffect(version) {
        if (follow) scroll.scrollTo(scroll.maxValue)
    }

    // key material + password, taken once per tab and remembered across retries
    val keyMat = remember(tab.id) { SessionKeys.take(tab.profile.id) }

    fun sendTermChar(ch: Char) {
        when {
            ch == '\n' -> tab.conn.send("\r")
            ctrl -> {
                val b = if (ch.isLetter()) CtrlKeys.ctrlByte(ch) else null
                if (b != null) tab.conn.send(CtrlKeys.byteString(b)) else tab.conn.send(ch.toString())
            }
            alt -> tab.conn.send(CtrlKeys.altSeq(ch))
            else -> tab.conn.send(ch.toString())
        }
    }

    Column(modifier.fillMaxSize()) {
        // ---- toolbar ----
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { keysOpen = !keysOpen }) {
                Icon(
                    if (keysOpen) Icons.Filled.KeyboardHide else Icons.Filled.Keyboard,
                    "More keys",
                    tint = if (keysOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { fontSize = (fontSize - 1).coerceAtLeast(10) }) {
                Icon(Icons.Filled.TextDecrease, "Smaller font")
            }
            IconButton(onClick = { fontSize = (fontSize + 1).coerceAtMost(20) }) {
                Icon(Icons.Filled.TextIncrease, "Larger font")
            }
            FilterChip(selected = follow, onClick = { follow = !follow },
                label = { Text("Follow") },
                leadingIcon = { Icon(Icons.Filled.VerticalAlignBottom, null) })
            FilterChip(selected = localEcho, onClick = { localEcho = !localEcho },
                label = { Text("Echo") })
            FilterChip(selected = ctrl, onClick = { ctrl = !ctrl }, label = { Text("CTRL") })
            FilterChip(selected = alt, onClick = { alt = !alt }, label = { Text("ALT") })
        IconButton(onClick = {
            at.websters.tabbyandroid.ui.util.copySensitive(context, tab.conn.buffer.visibleText())
            Toast.makeText(context, "Screen copied", Toast.LENGTH_SHORT).show()
        }) { Icon(Icons.Filled.ContentCopy, "Copy screen") }
            IconButton(onClick = {
                clipboard.getText()?.text?.let { tab.conn.send(it) }
            }) { Icon(Icons.Filled.ContentPaste, "Paste") }
            IconButton(onClick = { tab.conn.buffer.reset() }) {
                Icon(Icons.Filled.DeleteSweep, "Clear screen")
            }
            if (state == SshState.CONNECTED) {
                IconButton(onClick = { tab.conn.close() }) {
                    Icon(Icons.Filled.LinkOff, "Disconnect", tint = MaterialTheme.colorScheme.error)
                }
            }
        }

        // ---- screen IS a text field: tapping focuses natively (framework path),
        // typing/deleting forwards straight into SSH, selection gives native copy.
        val rows = tab.conn.buffer.rows
        val primary = MaterialTheme.colorScheme.primary
        val rendered = remember(version, tab.id, primary) { renderScreen(snapshot, rows, primary) }
        var field by remember(tab.id) { mutableStateOf(TextFieldValue("")) }
        // last server text we have shown; used to reconcile without yanking
        // freshly typed text out from under the keyboard (that desyncs IMEs)
        var shownServerText by remember(tab.id) { mutableStateOf("") }
        // show live server output - but only when the user hasn't typed ahead
        // of it (their keystrokes were already forwarded at press time and the
        // echo will catch the screen up; yanking causes lost/double input)
        LaunchedEffect(rendered) {
            if (field.text == shownServerText) {
                field = TextFieldValue(rendered, TextRange(rendered.length))
            }
            shownServerText = rendered.text
        }
        Column(
            Modifier.weight(1f).fillMaxWidth()
                .background(Color.Black)
                .padding(8.dp)
                .verticalScroll(scroll),
        ) {
        BasicTextField(
            value = field,
            onValueChange = { nv ->
                if (nv.text != field.text) {
                    val (del, added) = diffEdit(field.text, nv.text)
                    repeat(del) { tab.conn.send(CtrlKeys.byteString(127.toByte())) }
                    for (ch in added) sendTermChar(ch)
                    // local echo (off by default): show keystrokes instantly without
                    // waiting for server round-trip; also makes input observable in tests
                    if (localEcho && added.isNotEmpty()) {
                        tab.conn.buffer.feed(added.toByteArray())
                    }
                }
                // always keep what the keyboard committed: resetting here would
                // desync Gboard's text model and eat keystrokes
                field = nv
            },
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize.sp,
                lineHeight = (fontSize + 5).sp,
            ),
            cursorBrush = SolidColor(Color.Transparent), // our own block cursor is rendered
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.None,
                autoCorrect = false,
            ),
            keyboardActions = KeyboardActions(
                // some keyboards send an IME action instead of a newline (esp. on
                // password fields): every action key means "run the line"
                onDone = { tab.conn.send("\r") },
                onGo = { tab.conn.send("\r") },
                onSearch = { tab.conn.send("\r") },
                onSend = { tab.conn.send("\r") },
            ),
        )
        }

        if (state == SshState.ERROR) {
            Text(
                connStatus,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        if (state != SshState.CONNECTED) {
                if (keyMat != null) {
                Text(
                    "🔑 key selected — password is the key passphrase (if any)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text(if (keyMat != null) "Key passphrase (if any)" else "Password") },
                    modifier = Modifier.weight(1f), singleLine = true,
                    visualTransformation = if (pwVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { pwVisible = !pwVisible }) {
                            Icon(if (pwVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "Show")
                        }
                    },
                )
                Button(onClick = {
                    scope.launch {
                        val r = tab.conn.connect(password, keyMat?.first, keyMat?.second)
                        if (r.isFailure && r.exceptionOrNull() is at.websters.tabbyandroid.data.ssh.UnknownHostKeyException) {
                            showHostKey = tab.conn.pendingHostKey
                        }
                    }
                }) { Text(if (state == SshState.CONNECTING) "…" else "Connect") }
            }
        }

        // ---- extended keyboard: symbols + arrows always visible, rest expands ----
        KeyRow(SYMBOL_KEYS) { tab.conn.send(it) }
        KeyRow(NAV_KEYS) { tab.conn.send(it) }
        AnimatedVisibility(visible = keysOpen) {
            Column {
                KeyRow(FN_KEYS) { tab.conn.send(it) }
                KeyRow(COMBO_KEYS) { tab.conn.send(it) }
            }
        }
    }

    showHostKey?.let { key ->
        AlertDialog(
            onDismissRequest = { showHostKey = null },
            title = { Text("Unknown host key") },
            text = { Text("First connection to ${tab.profile.host}. Verify the fingerprint out-of-band, then accept.\n\n$key") },
            confirmButton = {
                TextButton(onClick = {
                    showHostKey = null
                    scope.launch {
                        tab.conn.connect(password, keyMat?.first, keyMat?.second, acceptHostKey = true)
                    }
                }) { Text("Accept & connect") }
            },
            dismissButton = { TextButton(onClick = { showHostKey = null }) { Text("Cancel") } },
        )
    }
}

/**
 * Renders the terminal screen as styled text (colors + bold + block cursor),
 * which the screen text field displays and the user can select/copy natively.
 */
private fun renderScreen(
    snapshot: TerminalBuffer.Snapshot,
    rows: Int,
    primary: androidx.compose.ui.graphics.Color,
): AnnotatedString {
    return androidx.compose.ui.text.buildAnnotatedString {
        val visible = snapshot.lines.takeLast(rows)
        visible.forEachIndexed { i, line ->
            var fg = -1
            var bold = false
            val sb = StringBuilder()
            fun flush() {
                if (sb.isNotEmpty()) {
                    pushStyle(
                        SpanStyle(
                            color = termColor(fg, true),
                            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                        )
                    )
                    append(sb.toString())
                    pop()
                    sb.clear()
                }
            }
            line.forEachIndexed { idx, cell ->
                if (i == snapshot.cursorRow && idx == snapshot.cursorCol) {
                    flush()
                    pushStyle(SpanStyle(color = primary, fontWeight = FontWeight.Bold))
                    append("\u258A")
                    pop()
                    return@forEachIndexed
                }
                if (cell.fg != fg || cell.bold != bold) {
                    flush()
                    fg = cell.fg
                    bold = cell.bold
                }
                sb.append(cell.ch)
            }
            if (i == snapshot.cursorRow && snapshot.cursorCol >= line.size) {
                flush()
                pushStyle(SpanStyle(color = primary, fontWeight = FontWeight.Bold))
                append("\u258A")
                pop()
            }
            flush()
            if (i < visible.size - 1) append("\n")
        }
    }
}

@Composable
private fun KeyRow(keys: List<Pair<String, String>>, onSend: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
    ) {
        keys.forEach { (label, seq) ->
            TextButton(
                onClick = { onSend(seq) },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            ) {
                Text(label, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            }
        }
    }
}

// NOTE: ESC is factored into the ESC constant (0x1B) so no raw control bytes
// ever appear in source. Ctrl+X / Tab are written as explicit unicode escapes.
internal val SYMBOL_KEYS = listOf(
    "Esc" to ESC, "Tab" to "\u0009", "Enter" to "\r",
    "|" to "|", "~" to "~", "-" to "-", "_" to "_",
    "/" to "/", "\\" to "\\", ":" to ":", ";" to ";",
    "\"" to "\"", "'" to "'", "$" to "$", "&" to "&",
    "*" to "*", "=" to "=", "+" to "+", "!" to "!", "?" to "?", "#" to "#",
)
internal val NAV_KEYS = listOf(
    "<-" to ESC + "[D", "Up" to ESC + "[A", "Dn" to ESC + "[B", "->" to ESC + "[C",
    "Home" to ESC + "[H", "End" to ESC + "[F", "PgUp" to ESC + "[5~", "PgDn" to ESC + "[6~",
    "Ins" to ESC + "[2~", "Del" to ESC + "[3~",
)
internal val FN_KEYS = listOf(
    "F1" to ESC + "OP", "F2" to ESC + "OQ", "F3" to ESC + "OR", "F4" to ESC + "OS",
    "F5" to ESC + "[15~", "F6" to ESC + "[17~", "F7" to ESC + "[18~", "F8" to ESC + "[19~",
    "F9" to ESC + "[20~", "F10" to ESC + "[21~", "F11" to ESC + "[23~", "F12" to ESC + "[24~",
)
internal val COMBO_KEYS = listOf(
    "Ctrl+C" to "\u0003", "Ctrl+D" to "\u0004", "Ctrl+Z" to "\u001A",
    "Ctrl+A" to "\u0001", "Ctrl+E" to "\u0005", "Ctrl+K" to "\u000B",
    "Ctrl+L" to "\u000C", "Ctrl+U" to "\u0015", "Ctrl+W" to "\u0017", "Ctrl+R" to "\u0012",
)
