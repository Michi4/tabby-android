package at.websters.tabbyandroid.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
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
import at.websters.tabbyandroid.data.local.UiPrefs
import at.websters.tabbyandroid.data.ssh.CtrlKeys
import at.websters.tabbyandroid.data.ssh.SENDER_SENTINEL
import at.websters.tabbyandroid.data.ssh.SshState
import at.websters.tabbyandroid.data.ssh.TerminalBuffer
import at.websters.tabbyandroid.data.ssh.senderEdit
import at.websters.tabbyandroid.ui.state.TerminalTabsViewModel
import at.websters.tabbyandroid.ui.theme.statusColor
import at.websters.tabbyandroid.ui.theme.termColor
import kotlinx.coroutines.launch

internal const val ESC = "\u001B"

/**
 * Termius-like terminal: browser-style tabs, read-only screen that looks
 * like direct input (an invisible sender owns the typed line; every commit
 * goes straight into SSH exactly once; tap the screen to focus), sticky
 * CTRL/ALT toggles, collapsible extended keys, long-press to copy,
 * stick-to-bottom follow that never yanks scrolled-up reading. Tuned for
 * tall 144Hz panels (RedMagic 10 Pro): version-gated snapshots.
 */
@Composable
fun TerminalScreen(tabsVm: TerminalTabsViewModel) {
    val tabs by tabsVm.tabs.collectAsState()
    val activeId by tabsVm.active.collectAsState()
    val prefs by tabsVm.uiPrefs.collectAsState()
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
            // single tab: no strip needed, but keep a way to close it
            if (tabs.size == 1 && active != null) {
                IconButton(onClick = { tabsVm.close(active.id) }) {
                    Icon(Icons.Filled.Close, "Close tab")
                }
            }
        }

        // ---- tab strip (only for 2+ tabs, to leave room for the terminal) ----
        if (tabs.size > 1) {
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
            TerminalTabBody(tab = current, prefs = prefs, modifier = Modifier.weight(1f))
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
private fun TerminalTabBody(
    tab: TerminalTabsViewModel.Tab,
    prefs: UiPrefs,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val state by tab.conn.state.collectAsState()
    val connStatus by tab.conn.status.collectAsState()
    val version by tab.conn.buffer.updates.collectAsState()
    var showHostKey by remember { mutableStateOf<String?>(null) }
    // runtime toggles, seeded from Settings → Terminal (new tabs only)
    var follow by remember(tab.id) { mutableStateOf(prefs.follow) }
    var fontSize by remember(tab.id) { mutableIntStateOf(prefs.fontSize) }
    var pwVisible by remember { mutableStateOf(false) }
    var ctrl by remember(tab.id) { mutableStateOf(false) }
    var alt by remember(tab.id) { mutableStateOf(false) }
    // extended-key rows on screen: 3 -> 2 -> 1 -> hidden, cycles on toggle
    // (saveable: survives rotation per tab)
    var keyRows by rememberSaveable(tab.id) { mutableIntStateOf(prefs.keyRows) }
    var password by remember(tab.id) { mutableStateOf(SessionPasswords.take(tab.profile.id)) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var input by remember(tab.id) {
        mutableStateOf(TextFieldValue(SENDER_SENTINEL, TextRange(SENDER_SENTINEL.length)))
    }
    // post-submit IME replay guard (consumed by the next change, if any)
    var suppressReplay by remember(tab.id) { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()

    val snapshot = remember(version, tab.id) { tab.conn.buffer.snapshot() }
    // stick to bottom only while the user is already near it: reading
    // scrolled-up history must never yank, and fitting content never jumps
    val stickSlopPx = with(LocalDensity.current) { 64.dp.toPx() }
    LaunchedEffect(version) {
        if (follow && scroll.maxValue - scroll.value <= stickSlopPx) {
            scroll.scrollTo(scroll.maxValue)
        }
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

    // Submit (Return): run the line, then clear the sender for the next one.
    fun submitReturn() {
        suppressReplay = input.text.replace(SENDER_SENTINEL, "")
        tab.conn.send("\r")
        input = TextFieldValue(SENDER_SENTINEL, TextRange(SENDER_SENTINEL.length))
    }

    Column(modifier.fillMaxSize()) {
        // ---- toolbar ----
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { keyRows = if (keyRows <= 0) 3 else keyRows - 1 }) {
                Icon(
                    if (keyRows == 0) Icons.Filled.Keyboard else Icons.Filled.KeyboardHide,
                    "Key rows: $keyRows of 3 (tap to cycle)",
                    tint = if (keyRows == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
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

        // ---- screen is OUTPUT ONLY (read-only, selectable); typing goes
        // through the sender bar below, and tapping the screen focuses it.
        // The old editable-screen design fought the IME over server echo
        // (lost/doubled keystrokes); this split can't desync by construction:
        // every commit is forwarded exactly once, then the field resets.
        val rows = tab.conn.buffer.rows
        val primary = MaterialTheme.colorScheme.primary
        val rendered = remember(version, tab.id, primary) { renderScreen(snapshot, rows, primary) }
        Column(
            Modifier.weight(1f).fillMaxWidth()
                .background(Color.Black)
                .padding(8.dp)
                .verticalScroll(scroll)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    // show() too: after an explicit BACK-close the system
                    // won't re-open on focus alone
                    onClick = { focusRequester.requestFocus(); keyboard?.show() },
                ),
        ) {
            SelectionContainer {
                Text(
                    text = rendered,
                    modifier = Modifier.fillMaxWidth(),
                    style = TextStyle(
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize + 5).sp,
                    ),
                )
            }
        }
        // ---- sender bar: accumulates the line (Gboard owns the text, we only
        // diff-forward, so the IME can never desync); server echo stays in the
        // output view and never enters the field. A zero-width sentinel keeps
        // soft-keyboard backspace observable on the empty field; hardware
        // ENTER/DEL arrive via onKeyEvent (singleLine eats \n). Submit clears.
        BasicTextField(
            value = input,
            onValueChange = { nv ->
                val clean = nv.text.replace(SENDER_SENTINEL, "")
                // swallow the single post-submit replay some IMEs emit after
                // an app-driven clear (would otherwise resend the line)
                if (suppressReplay != null) {
                    val replay = suppressReplay
                    suppressReplay = null
                    if (clean == replay) {
                        input = TextFieldValue(SENDER_SENTINEL, TextRange(SENDER_SENTINEL.length))
                        return@BasicTextField
                    }
                }
                val edit = senderEdit(input.text, nv.text)
                if (edit.sendText.isEmpty() && edit.deletions == 0) {
                    input = nv // cursor/selection move only — preserve it
                } else {
                    repeat(edit.deletions) { tab.conn.send(CtrlKeys.byteString(127.toByte())) }
                    for (ch in edit.sendText) sendTermChar(ch)
                    input = nv
                }
            },
            // invisible (1dp, transparent) but focusable: the screen looks
            // like direct terminal input; server echo shows what you type
            modifier = Modifier.fillMaxWidth()
                .height(1.dp)
                .alpha(0f)
                .focusRequester(focusRequester)
                .onKeyEvent {
                    if (it.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (it.key) {
                        Key.Enter, Key.NumPadEnter -> { submitReturn(); true }
                        // soft keyboards delete via InputConnection (handled above);
                        // a hardware DEL on the sentinel-only field must send DEL
                        // manually (consuming also keeps the sentinel intact).
                        Key.Backspace -> if (input.text == SENDER_SENTINEL) {
                            tab.conn.send(CtrlKeys.byteString(127.toByte())); true
                        } else false
                        else -> false
                    }
                },
            textStyle = TextStyle(
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize.sp,
                lineHeight = (fontSize + 5).sp,
            ),
            cursorBrush = SolidColor(Color.White),
            singleLine = true,
            // Password type (with visible text): the only reliable way to get
            // zero suggestions/autocorrect/gesture — a terminal must send
            // exactly what the user typed ("row-ok", never "Rowling").
            visualTransformation = VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrect = false,
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { submitReturn() }),
        )

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
                        } else if (r.isSuccess) {
                            // ready to type: focus the sender, pop the keyboard
                            focusRequester.requestFocus()
                            keyboard?.show()
                        }
                    }
                }) { Text(if (state == SshState.CONNECTING) "…" else "Connect") }
            }
        }

        // ---- extended keys: ride above the keyboard via IME insets, visible
        // at all times; arrows on top, symbols (Enter) second, F-keys third.
        // 8dp below mirrors the output padding above for symmetry. Combos live
        // in the CTRL/ALT toggles + keyboard (no redundant rows).
        // the Enter key submits (sends CR + clears the sender like a real Return)
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).imePadding()) {
            if (keyRows >= 1) {
                KeyRow(NAV_KEYS) { tab.conn.send(it) }
            }
            if (keyRows >= 2) {
                KeyRow(SYMBOL_KEYS) { seq -> if (seq == "\r") submitReturn() else tab.conn.send(seq) }
            }
            if (keyRows >= 3) {
                KeyRow(FN_KEYS) { tab.conn.send(it) }
            }
        }
    }

    showHostKey?.let { key ->
        val changed = tab.conn.pendingHostKeyChanged
        AlertDialog(
            onDismissRequest = { showHostKey = null },
            title = { Text(if (changed) "Server host key changed" else "Unknown host key") },
            text = {
                Column {
                    if (changed) {
                        Text(
                            "Warning: this can mean an attack in progress. Accept only if you reinstalled the server or its SSH keys on purpose.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        Text("First connection to ${tab.profile.host}. Verify the fingerprint out-of-band, then accept.")
                    }
                    Text(key, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showHostKey = null
                    scope.launch {
                        val r = tab.conn.connect(password, keyMat?.first, keyMat?.second, acceptHostKey = true)
                        if (r.isSuccess) {
                            focusRequester.requestFocus()
                            keyboard?.show()
                        }
                    }
                }) { Text(if (changed) "Accept new key & connect" else "Accept & connect") }
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
