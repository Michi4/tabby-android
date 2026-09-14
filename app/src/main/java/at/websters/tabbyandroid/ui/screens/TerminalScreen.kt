package at.websters.tabbyandroid.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
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
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import at.websters.tabbyandroid.data.local.UiPrefs
import at.websters.tabbyandroid.data.local.UiPrefsDefaults
import at.websters.tabbyandroid.data.ssh.CtrlKeys
import at.websters.tabbyandroid.data.ssh.DEMO_SHELL_PREFIX
import at.websters.tabbyandroid.data.ssh.SENDER_SENTINEL
import at.websters.tabbyandroid.data.ssh.SshState
import at.websters.tabbyandroid.data.ssh.TerminalBuffer
import at.websters.tabbyandroid.data.ssh.senderEdit
import at.websters.tabbyandroid.ui.state.ConnectionsViewModel
import at.websters.tabbyandroid.ui.state.SshKeysViewModel
import at.websters.tabbyandroid.ui.state.TerminalTabsViewModel
import at.websters.tabbyandroid.ui.theme.statusColor
import at.websters.tabbyandroid.ui.theme.statusLabel
import at.websters.tabbyandroid.ui.theme.termColor
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

internal const val ESC = "\u001B"

/** One-shot/locked modifier state: tap = one-shot, tap again = lock, tap again = off. */
internal enum class ModMode { OFF, ONE_SHOT, LOCKED }

internal fun nextModMode(cur: ModMode): ModMode = when (cur) {
    ModMode.OFF -> ModMode.ONE_SHOT
    ModMode.ONE_SHOT -> ModMode.LOCKED
    ModMode.LOCKED -> ModMode.OFF
}

/**
 * Termius-like terminal: browser-style tabs, read-only screen that looks
 * like direct input (an invisible sender owns the typed line; every commit
 * goes straight into SSH exactly once; tap the screen to focus), one-shot
 * CTRL/ALT/ALTGR in the top key row (tap = next key only, double-tap = lock),
 * collapsible extended keys, fullscreen mode, long-press to copy,
 * stick-to-bottom follow that never yanks scrolled-up reading.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalScreen(
    tabsVm: TerminalTabsViewModel,
    connectionsVm: ConnectionsViewModel = viewModel(),
    keysVm: SshKeysViewModel = viewModel(),
) {
    val tabs by tabsVm.tabs.collectAsState()
    val activeId by tabsVm.active.collectAsState()
    val prefs by tabsVm.uiPrefs.collectAsState()
    var showQuick by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = !prefs.fullscreen,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
        ) {
            Column {
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
                        Text(
                            "●",
                            color = statusColor(st),
                            fontSize = 10.sp,
                            modifier = Modifier.clearAndSetSemantics {
                                contentDescription = statusLabel(st)
                            },
                        )
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
            }
        }

        val current = tabs.find { it.id == activeId } ?: tabs.lastOrNull()
        if (current == null) {
            Card(Modifier.padding(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No terminal tabs", style = MaterialTheme.typography.titleMedium)
                    Text("Open a host from Hosts, tap + at the top right, or start here:")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { showQuick = true }) { Text("Quick connect") }
                        OutlinedButton(onClick = { tabsVm.openDemoShell() }) { Text("Demo shell") }
                    }
                }
            }
        } else {
            // swipe-style tab switch: slides in the tab order direction
            AnimatedContent(
                targetState = current.id,
                transitionSpec = {
                    val from = tabs.indexOfFirst { it.id == initialState }
                    val to = tabs.indexOfFirst { it.id == targetState }
                    val dir = if (to >= from) 1 else -1
                    (slideInHorizontally(tween(250)) { it * dir } + fadeIn(tween(250)))
                        .togetherWith(slideOutHorizontally(tween(250)) { -it * dir } + fadeOut(tween(250)))
                },
                label = "terminal-tabs",
                modifier = Modifier.weight(1f),
            ) { id ->
                val shown = tabs.find { it.id == id } ?: current
                TerminalTabBody(
                    tab = shown,
                    prefs = prefs,
                    tabsVm = tabsVm,
                    connectionsVm = connectionsVm,
                    keysVm = keysVm,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    if (showQuick) {
        var quick by remember { mutableStateOf("") }
        var quickError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showQuick = false },
            title = { Text("Quick connect") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = quick,
                        onValueChange = { quick = it; quickError = null },
                        label = { Text("user@host:port") }, singleLine = true,
                        isError = quickError != null,
                        supportingText = { if (quickError != null) Text(quickError!!) },
                    )
                    OutlinedButton(
                        onClick = {
                            tabsVm.openDemoShell()
                            showQuick = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Try the demo shell (no server)") }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // validate before opening: no blank-host tab that can
                        // only fail later as a DNS error
                        if (connectionsVm.quickConnect(quick).host.isBlank()) {
                            quickError = "Enter a host (user@host:port)"
                        } else if (quick.isNotBlank()) {
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TerminalTabBody(
    tab: TerminalTabsViewModel.Tab,
    prefs: UiPrefs,
    tabsVm: TerminalTabsViewModel,
    connectionsVm: ConnectionsViewModel,
    keysVm: SshKeysViewModel,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val state by tab.conn.state.collectAsState()
    val connStatus by tab.conn.status.collectAsState()
    val version by tab.conn.buffer.updates.collectAsState()
    val allKeys by keysVm.keys.collectAsState()
    var showHostKey by remember { mutableStateOf<String?>(null) }
    // runtime toggles, seeded from Settings → Terminal (new tabs only)
    var follow by remember(tab.id) { mutableStateOf(prefs.follow) }
    var fontSize by remember(tab.id) { mutableIntStateOf(prefs.fontSize) }
    var pwVisible by remember { mutableStateOf(false) }
    // one-shot modifiers live in the top key row (tap = next key, 2×tap = lock)
    var ctrlMode by remember(tab.id) { mutableStateOf(ModMode.OFF) }
    var altMode by remember(tab.id) { mutableStateOf(ModMode.OFF) }
    var altGrMode by remember(tab.id) { mutableStateOf(ModMode.OFF) }
    // extended-key rows on screen: 3 -> 2 -> 1 -> hidden, cycles on toggle
    // (saveable: survives rotation per tab)
    var keyRows by rememberSaveable(tab.id) { mutableIntStateOf(prefs.keyRows) }
    // ephemeral creds (just-entered) win; stored creds (encrypted) are the fallback
    // so reconnect works after backgrounding / process death without re-typing
    val ephemeralPw = remember(tab.id) { SessionPasswords.take(tab.profile.id) }
    val ephemeralKey = remember(tab.id) { SessionKeys.take(tab.profile.id) }
    val storedPw = remember(tab.profile.id, allKeys) { connectionsVm.getPassword(tab.profile.id) }
    val storedKey = remember(tab.profile.id, allKeys) {
        tab.profile.keyId?.let { kid -> keysVm.loadKey(kid) }
    }
    val keyMat = ephemeralKey ?: storedKey
    val savedPw = if (ephemeralPw.isNotBlank()) ephemeralPw else storedPw
    var password by remember(tab.id) {
        mutableStateOf(if (ephemeralPw.isNotBlank()) ephemeralPw else "")
    }
    var showPwField by remember(tab.id) {
        mutableStateOf(keyMat == null && savedPw.isBlank())
    }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var inputFocused by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.isImeVisible
    var input by remember(tab.id) {
        mutableStateOf(TextFieldValue(SENDER_SENTINEL, TextRange(SENDER_SENTINEL.length)))
    }
    // post-submit IME replay guard (consumed by the next change, if any)
    var suppressReplay by remember(tab.id) { mutableStateOf<String?>(null) }
    var showMacros by remember { mutableStateOf(false) }
    val histTick by tabsVm.historyTick.collectAsState()
    // find-in-scrollback state
    var searching by remember { mutableStateOf(false) }
    var query by remember(tab.id) { mutableStateOf("") }
    var matchSel by remember(tab.id) { mutableIntStateOf(0) }
    val scroll = rememberScrollState()

    val snapshot = remember(version, tab.id) { tab.conn.buffer.snapshot() }
    // scrollback cap follows Settings (live)
    LaunchedEffect(prefs.scrollback, tab.id) {
        tab.conn.buffer.updateMaxScrollback(prefs.scrollback)
    }
    // stick to bottom only while the user is already near it: reading
    // scrolled-up history must never yank, and fitting content never jumps
    val stickSlopPx = with(LocalDensity.current) { 64.dp.toPx() }
    LaunchedEffect(version) {
        if (follow && scroll.maxValue - scroll.value <= stickSlopPx) {
            scroll.scrollTo(scroll.maxValue)
        }
    }

    fun clearOneShots() {
        if (ctrlMode == ModMode.ONE_SHOT) ctrlMode = ModMode.OFF
        if (altMode == ModMode.ONE_SHOT) altMode = ModMode.OFF
        if (altGrMode == ModMode.ONE_SHOT) altGrMode = ModMode.OFF
    }

    fun sendWithMods(seq: String) {
        val c = ctrlMode != ModMode.OFF
        val a = altMode != ModMode.OFF || altGrMode != ModMode.OFF
        tab.conn.send(CtrlKeys.withModifiers(seq, c, a))
        clearOneShots()
    }

    fun sendTermChar(ch: Char) {
        if (ch == '\n') {
            sendWithMods("\r")
            return
        }
        val c = ctrlMode != ModMode.OFF
        val a = altMode != ModMode.OFF || altGrMode != ModMode.OFF
        if (!c && !a) {
            tab.conn.send(ch.toString())
        } else {
            tab.conn.send(CtrlKeys.withModifiers(ch.toString(), c, a))
        }
        clearOneShots()
    }

    // Submit (Return): run the line, then clear the sender for the next one.
    fun submitReturn() {
        val cmd = input.text.replace(SENDER_SENTINEL, "")
        suppressReplay = cmd
        if (cmd.isNotBlank()) tabsVm.recordCommand(cmd)
        sendWithMods("\r")
        input = TextFieldValue(SENDER_SENTINEL, TextRange(SENDER_SENTINEL.length))
    }

    /** Fills the line for review (suggestion/macro tap) without sending. */
    fun fillLine(text: String) {
        // direct state write: onValueChange does NOT fire for programmatic
        // sets, so nothing is forwarded — the user reviews, edits, submits
        input = TextFieldValue(SENDER_SENTINEL + text, TextRange(SENDER_SENTINEL.length + text.length))
    }

    fun doConnect(pw: String) {
        scope.launch {
            val r = tab.conn.connect(pw, keyMat?.first, keyMat?.second)
            if (r.isFailure && r.exceptionOrNull() is at.websters.tabbyandroid.data.ssh.UnknownHostKeyException) {
                showHostKey = tab.conn.pendingHostKey
            } else if (r.isSuccess) {
                // ready to type: focus the sender, pop the keyboard.
                // Pinned to Main: focus/keyboard are UI ops and the resuming
                // context is not guaranteed Main (e.g. under UI-test dispatchers).
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    focusRequester.requestFocus()
                    keyboard?.show()
                }
            }
        }
    }

    // demo shell needs no auth: start it as soon as the tab opens
    val isDemo = tab.profile.id.startsWith(DEMO_SHELL_PREFIX)
    LaunchedEffect(tab.id) {
        if (isDemo && tab.conn.state.value == SshState.DISCONNECTED) doConnect("")
    }

    Column(modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = !prefs.fullscreen,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
        ) {
            // ---- toolbar (modifiers live in the top key row now, not here) ----
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { keyRows = if (keyRows <= 0) 4 else keyRows - 1 }) {
                    Icon(
                        if (keyRows == 0) Icons.Filled.Keyboard else Icons.Filled.KeyboardHide,
                        "Key rows: $keyRows (tap to cycle)",
                        tint = if (keyRows == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = { fontSize = (fontSize - 1).coerceAtLeast(UiPrefsDefaults.FONT_MIN) }) {
                    Icon(Icons.Filled.TextDecrease, "Smaller font")
                }
                IconButton(onClick = { fontSize = (fontSize + 1).coerceAtMost(UiPrefsDefaults.FONT_MAX) }) {
                    Icon(Icons.Filled.TextIncrease, "Larger font")
                }
                FilterChip(selected = follow, onClick = { follow = !follow },
                    label = { Text("Follow") },
                    leadingIcon = { Icon(Icons.Filled.VerticalAlignBottom, null) })
                IconButton(onClick = { tabsVm.setUiFullscreen(true) }) {
                    Icon(Icons.Filled.Fullscreen, "Fullscreen")
                }
                IconButton(onClick = { showMacros = true }) {
                    Icon(Icons.Filled.ElectricBolt, "Macros")
                }
                IconButton(onClick = {
                    searching = !searching
                    if (!searching) query = ""
                }) {
                    Icon(
                        Icons.Filled.Search, "Find in scrollback",
                        tint = if (searching) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
        }

        // ---- find matches (absolute deque indices → visible rows); selection
        // wraps around, auto-scroll only follows query/selection changes
        val rows = tab.conn.buffer.rows
        val matchAbs = remember(version, query, tab.id) {
            tab.conn.buffer.searchLines(query)
        }
        val findTotal = matchAbs.size
        val selIdx = if (findTotal == 0) 0 else ((matchSel % findTotal) + findTotal) % findTotal
        val findShown = selIdx
        val base = tab.conn.buffer.visibleBase()
        val matchRows = remember(matchAbs, base) {
            matchAbs.mapNotNull { (it - base).takeIf { r -> r in 0 until rows } }.toSet()
        }
        val currentRow = matchAbs.getOrNull(selIdx)?.minus(base)
            ?.takeIf { it in 0 until rows }
        val lineHpx = with(LocalDensity.current) { (fontSize + 5).sp.toPx() }
        LaunchedEffect(query, matchSel) {
            currentRow?.let { r ->
                scroll.scrollTo((r * lineHpx).toInt().coerceIn(0, scroll.maxValue))
            }
        }

        // ---- find bar (searches the whole buffer incl. scrollback) ----
        AnimatedVisibility(
            visible = searching,
            enter = expandVertically(tween(180)) + fadeIn(tween(180)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(180)),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; matchSel = 0 },
                    label = { Text("Find in scrollback") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Text(
                    if (findTotal == 0) "0/0" else "${findShown + 1}/$findTotal",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = { matchSel = matchSel - 1 }) {
                    Icon(Icons.Filled.KeyboardArrowUp, "Previous match")
                }
                IconButton(onClick = { matchSel = matchSel + 1 }) {
                    Icon(Icons.Filled.KeyboardArrowDown, "Next match")
                }
                IconButton(onClick = { searching = false; query = "" }) {
                    Icon(Icons.Filled.Close, "Close search")
                }
            }
        }

        // ---- screen is OUTPUT ONLY (read-only, selectable); typing goes
        // through the sender bar below, and tapping the screen focuses it.
        // Viewport measurement (Termius-style): derive the real character
        // cells from the laid-out size + font, and keep buffer + pty in sync
        // — so TUIs redraw on font, key-row, keyboard, fullscreen and
        // rotation changes instead of staying stuck at 80x24.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val density = LocalDensity.current
            val measurer = rememberTextMeasurer()
            val cellW = remember(fontSize) {
                measurer.measure(
                    AnnotatedString("0123456789"),
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = fontSize.sp,
                    ),
                ).size.width / 10f
            }.coerceAtLeast(1f)
            val lineH = with(density) { (fontSize + 5).sp.toPx() }.coerceAtLeast(1f)
            val pad = with(density) { 16.dp.toPx() }
            val viewCols = (((with(density) { maxWidth.toPx() } - pad) / cellW).toInt())
                .coerceIn(20, 300)
            val viewRows = (((with(density) { maxHeight.toPx() } - pad) / lineH).toInt())
                .coerceIn(5, 200)
            LaunchedEffect(viewCols, viewRows, tab.id) {
                tab.conn.buffer.resize(viewCols, viewRows)
                tab.conn.setPtySize(viewCols, viewRows)
            }
            val primary = MaterialTheme.colorScheme.primary
            val rendered = remember(version, tab.id, primary, query, currentRow) {
                renderScreen(snapshot, rows, primary, matchRows, currentRow ?: -1)
            }
            // pinch-to-zoom font (two fingers only — single-finger tap,
            // scroll and long-press selection pass through untouched)
            val pinch = rememberTransformableState { zoomChange, _, _ ->
                fontSize = ((fontSize * zoomChange).roundToInt())
                    .coerceIn(UiPrefsDefaults.FONT_MIN, UiPrefsDefaults.FONT_MAX)
            }
            Column(
                Modifier.fillMaxSize()
                    .background(Color.Black)
                    .padding(8.dp)
                    .transformable(pinch, lockRotationOnZoomPan = true, enabled = prefs.pinchZoom)
                    .verticalScroll(scroll)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { focusRequester.requestFocus(); keyboard?.show() },
                    ),
            ) {
                SelectionContainer {
                    // NEVER soft-wrap: buffer lines are exactly cols wide and
                    // must map 1:1 to visual rows (a wrapped logo/table looks
                    // "cut off", like Termius never does). Overflow scrolls
                    // horizontally instead.
                    Text(
                        text = rendered,
                        modifier = Modifier.fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        softWrap = false,
                        style = TextStyle(
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontSize = fontSize.sp,
                            lineHeight = (fontSize + 5).sp,
                        ),
                    )
                }
            }
            if (prefs.fullscreen) {
                // floating exit (translucent, out of the way)
                IconButton(
                    onClick = { tabsVm.setUiFullscreen(false) },
                    modifier = Modifier.align(Alignment.TopEnd)
                        .padding(4.dp)
                        .alpha(0.7f),
                ) {
                    Icon(
                        Icons.Filled.FullscreenExit, "Exit fullscreen",
                        tint = Color.White,
                    )
                }
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
                .onFocusChanged { inputFocused = it.isFocused }
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
            visualTransformation = VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrect = false,
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { submitReturn() }),
        )

        if (state == SshState.ERROR && !isDemo) {
            Text(
                connStatus,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        if (isDemo) {
            if (state == SshState.ERROR) {
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        connStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { doConnect("") }) { Text("Restart") }
                }
            } else if (state == SshState.DISCONNECTED && connStatus.isNotBlank()) {
                // shell exited (e.g. Ctrl+D): one tap restarts, no auth needed
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        connStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { doConnect("") }) { Text("Restart") }
                }
            }
        } else if (state != SshState.CONNECTED) {
            if (keyMat != null) {
                Text(
                    "🔑 key selected — password is the key passphrase (if any)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            if (!showPwField && (keyMat != null || savedPw.isNotBlank())) {
                // creds already saved: one tap reconnects, no password box
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when {
                            keyMat != null && savedPw.isNotBlank() -> "Key + saved passphrase ready"
                            keyMat != null -> "Key ready — no passphrase needed?"
                            else -> "Saved password ready"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { showPwField = true }) { Text("Change") }
                    Button(onClick = { doConnect(password.ifBlank { savedPw }) }) {
                        if (state == SshState.CONNECTING) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else if (connStatus == "Disconnected") {
                            Text("Reconnect")
                        } else {
                            Text("Connect")
                        }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = password, onValueChange = { password = it },
                        label = { Text(if (keyMat != null) "Key passphrase (if any)" else "Password") },
                        modifier = Modifier.weight(1f), singleLine = true,
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
                    Button(onClick = { doConnect(password.ifBlank { savedPw }) }) {
                        if (state == SshState.CONNECTING) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Connect")
                        }
                    }
                }
            }
        }

        // ---- history suggestions (tap a chip to fill the line for review;
        // frequency-ranked, learned from submitted commands, toggle in Settings)
        val currentLine = input.text.replace(SENDER_SENTINEL, "")
        val suggestions = remember(currentLine, histTick) {
            if (!prefs.suggestions) emptyList()
            else at.websters.tabbyandroid.data.local.rankSuggestions(tabsVm.loadHistory(), currentLine)
        }
        AnimatedVisibility(
            visible = suggestions.isNotEmpty(),
            enter = expandVertically(tween(180)) + fadeIn(tween(180)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(180)),
        ) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                suggestions.forEach { s ->
                    AssistChip(
                        onClick = { fillLine(s) },
                        label = {
                            Text(
                                s,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                            )
                        },
                    )
                }
            }
        }

        // ---- extended keys: ride above the keyboard via IME insets.
        // Row 1 (modifiers + arrows): Esc Tab CTRL ← ↑ ↓ → ALT AltGr —
        // CTRL/ALT/AltGr are one-shot (tap = next key only, double-tap = lock).
        // Row 2: Enter + editing + symbols. Row 3: F1–F12.
        val showKeyRows = if (prefs.fullscreen) {
            keyRows > 0 && (inputFocused || imeVisible)
        } else {
            true
        }
        AnimatedVisibility(
            visible = showKeyRows,
            enter = expandVertically(tween(220)) + fadeIn(tween(220)),
            exit = shrinkVertically(tween(220)) + fadeOut(tween(220)),
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).imePadding()) {
                // rows come from Settings → Key layout (first keyRows of them);
                // modifiers are one-shot (tap = next key, double-tap = lock)
                prefs.keyLayout.rows.take(keyRows.coerceIn(0, 4)).forEach { ids ->
                    TerminalKeyRow(
                        ids = ids,
                        spacingDp = prefs.keyLayout.spacingDp,
                        ctrlMode = ctrlMode,
                        altMode = altMode,
                        altGrMode = altGrMode,
                        onModifier = { which ->
                            when (which) {
                                "ctrl" -> ctrlMode = nextModMode(ctrlMode)
                                "alt" -> altMode = nextModMode(altMode)
                                else -> altGrMode = nextModMode(altGrMode)
                            }
                        },
                        onSend = ::sendWithMods,
                        onSubmitReturn = ::submitReturn,
                    )
                }
            }
        }
    }

    if (showMacros) {
        MacrosDialog(
            tabsVm = tabsVm,
            onFill = { fillLine(it); showMacros = false },
            onRun = { cmd ->
                tab.conn.send(cmd)
                if (!cmd.endsWith("\n")) tab.conn.send("\r")
                tabsVm.recordCommand(cmd.trim())
                showMacros = false
            },
            onDismiss = { showMacros = false },
        )
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
                        val r = tab.conn.connect(
                            password.ifBlank { savedPw }, keyMat?.first, keyMat?.second,
                            acceptHostKey = true,
                        )
                        if (r.isSuccess) {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                                focusRequester.requestFocus()
                                keyboard?.show()
                            }
                        }
                    }
                }) { Text(if (changed) "Accept new key & connect" else "Accept & connect") }
            },
            dismissButton = { TextButton(onClick = { showHostKey = null }) { Text("Cancel") } },
        )
    }
}

/**
 * URLs in a plain terminal line: (char range in plain text, url).
 * Trailing punctuation/brackets are trimmed so `see https://x.y/z.` links
 * exactly the address. Pure logic, unit-tested.
 */
internal fun findLinks(text: String): List<Pair<IntRange, String>> {
    val out = mutableListOf<Pair<IntRange, String>>()
    val re = Regex("""https?://[^\s)>\]"']+""")
    for (m in re.findAll(text)) {
        var end = m.range.last
        while (end >= m.range.first && text[end] in ".,;:!?") end--
        if (end < m.range.first) continue
        out.add((m.range.first..end) to text.substring(m.range.first, end + 1))
    }
    return out
}

/**
 * Renders the terminal screen as styled text (colors + bold + block cursor),
 * which the screen text field displays and the user can select/copy natively.
 */
private fun renderScreen(
    snapshot: TerminalBuffer.Snapshot,
    rows: Int,
    primary: androidx.compose.ui.graphics.Color,
    matchLines: Set<Int> = emptySet(),
    currentMatch: Int = -1,
): AnnotatedString {
    val linkStyle = TextLinkStyles(
        style = SpanStyle(
            color = primary,
            textDecoration = TextDecoration.Underline,
        )
    )
    return buildAnnotatedString {
        val visible = snapshot.lines.takeLast(rows)
        visible.forEachIndexed { i, line ->
            var fg = -1
            var bg = -1
            var bold = false
            var reverse = false
            var underline = false
            var dim = false
            var segLink: String? = null
            // plain-text index per cell (wide second-halves share no index)
            val plainOf = IntArray(line.size) { -1 }
            var pp = 0
            line.forEachIndexed { idx, cell ->
                if (!cell.wide2nd) {
                    plainOf[idx] = pp
                    pp++
                }
            }
            val plainLen = pp
            val links = findLinks(
                buildString {
                    line.forEachIndexed { idx, cell ->
                        if (plainOf[idx] >= 0) append(cell.ch)
                    }
                }
            )
            fun linkAt(p: Int): String? {
                if (p < 0) return null
                for ((range, url) in links) {
                    if (p in range) return url
                }
                return null
            }
            val sb = StringBuilder()
            fun flush() {
                if (sb.isNotEmpty()) {
                    val fgColor = termColor(fg.coerceIn(0, 7), true).let {
                        if (dim) it.copy(alpha = 0.6f) else it
                    }
                    val bgColor = termColor(bg.coerceIn(0, 7), true)
                    val matchBg = when {
                        i == currentMatch -> primary.copy(alpha = 0.45f)
                        i in matchLines -> primary.copy(alpha = 0.22f)
                        else -> Color.Unspecified
                    }
                    val useLink = segLink != null
                    val color = when {
                        useLink -> primary
                        reverse -> bgColor
                        else -> fgColor
                    }
                    val background = when {
                        reverse -> fgColor
                        matchBg != Color.Unspecified -> matchBg
                        else -> Color.Unspecified
                    }
                    val deco = if (underline || useLink) TextDecoration.Underline else null
                    if (useLink) {
                        // base carries bg/weight; the link annotation itself
                        // carries color + underline (tap opens the URL)
                        pushStyle(
                            SpanStyle(
                                background = background,
                                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                            )
                        )
                        pushLink(LinkAnnotation.Url(segLink!!, linkStyle))
                        append(sb.toString())
                        pop()
                        pop()
                    } else {
                        pushStyle(
                            SpanStyle(
                                color = color,
                                background = background,
                                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                                textDecoration = deco,
                            )
                        )
                        append(sb.toString())
                        pop()
                    }
                    sb.clear()
                }
            }
            line.forEachIndexed { idx, cell ->
                if (snapshot.cursorVisible && i == snapshot.cursorRow && idx == snapshot.cursorCol) {
                    flush()
                    pushStyle(SpanStyle(color = primary, fontWeight = FontWeight.Bold))
                    append("\u258A")
                    pop()
                    return@forEachIndexed
                }
                val cellLink = linkAt(plainOf[idx])
                if (cell.fg != fg || cell.bg != bg || cell.bold != bold ||
                    cell.reverse != reverse || cell.underline != underline || cell.dim != dim ||
                    cellLink != segLink
                ) {
                    flush()
                    fg = cell.fg
                    bg = cell.bg
                    bold = cell.bold
                    reverse = cell.reverse
                    underline = cell.underline
                    dim = cell.dim
                    segLink = cellLink
                }
                sb.append(cell.ch)
            }
            if (snapshot.cursorVisible && i == snapshot.cursorRow && snapshot.cursorCol >= line.size) {
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

/**
 * One customizable key row (Settings → Key layout reuses this for the live
 * preview). Modifier ids render as one-shot chips, up/down as arrow icons
 * (content-described, no text needed), Enter submits, everything else sends
 * its byte sequence. Unknown ids are skipped, never crash.
 */
@Composable
internal fun TerminalKeyRow(
    ids: List<String>,
    spacingDp: Int,
    ctrlMode: ModMode,
    altMode: ModMode,
    altGrMode: ModMode,
    onModifier: (String) -> Unit,
    onSend: (String) -> Unit,
    onSubmitReturn: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(spacingDp.coerceIn(0, 16).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ids.forEach { id ->
            when (id) {
                "ctrl" -> ModChip(label = "Ctrl", mode = ctrlMode, onClick = { onModifier("ctrl") })
                "alt" -> ModChip(label = "Alt", mode = altMode, onClick = { onModifier("alt") })
                "altgr" -> ModChip(label = "AltGr", mode = altGrMode, onClick = { onModifier("altgr") })
                "up" -> IconButton(
                    onClick = { at.websters.tabbyandroid.data.local.keySeqFor("up")?.let(onSend) },
                    modifier = Modifier.size(36.dp),
                ) { Icon(Icons.Filled.KeyboardArrowUp, "Up") }
                "down" -> IconButton(
                    onClick = { at.websters.tabbyandroid.data.local.keySeqFor("down")?.let(onSend) },
                    modifier = Modifier.size(36.dp),
                ) { Icon(Icons.Filled.KeyboardArrowDown, "Down") }
                "enter" -> TextButton(
                    onClick = onSubmitReturn,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                ) { Text("Enter", fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
                else -> {
                    val seq = at.websters.tabbyandroid.data.local.keySeqFor(id)
                    if (seq != null) {
                        TextButton(
                            onClick = { onSend(seq) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        ) {
                            Text(
                                at.websters.tabbyandroid.data.local.keyLabelFor(id),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}
/**
 * Saved macros: tap a row to fill the line for review, ▶ to run it
 * immediately (command + Enter), × to delete. Stored encrypted.
 */
@Composable
private fun MacrosDialog(
    tabsVm: TerminalTabsViewModel,
    onFill: (String) -> Unit,
    onRun: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val tick by tabsVm.macrosTick.collectAsState()
    val macros = remember(tick) { tabsVm.loadMacros() }
    var name by remember { mutableStateOf("") }
    var cmd by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Macros") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (macros.isEmpty()) {
                    Text(
                        "No macros yet — save a command below, then tap it to fill " +
                            "the line or ▶ to run it at once.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                macros.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            Modifier.weight(1f).clickable { onFill(m.command) },
                        ) {
                            Text(m.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                m.command,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { onRun(m.command) }) {
                            Icon(Icons.Filled.PlayArrow, "Run ${m.name}")
                        }
                        IconButton(onClick = { tabsVm.deleteMacro(m.name) }) {
                            Icon(Icons.Filled.Close, "Delete ${m.name}")
                        }
                    }
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") }, singleLine = true,
                )
                OutlinedTextField(
                    value = cmd, onValueChange = { cmd = it },
                    label = { Text("Command") }, singleLine = true,
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                )
                Button(
                    onClick = {
                        tabsVm.addMacro(name, cmd)
                        name = ""
                        cmd = ""
                    },
                    enabled = name.isNotBlank() && cmd.isNotBlank(),
                ) { Text("Save macro") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
internal fun ModChip(label: String, mode: ModMode, onClick: () -> Unit) {    val text = when (mode) {
        ModMode.OFF -> label
        ModMode.ONE_SHOT -> "$label•"
        ModMode.LOCKED -> "$label▪"
    }
    FilterChip(
        selected = mode != ModMode.OFF,
        onClick = onClick,
        label = { Text(text, fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
    )
}

// NOTE: ESC is factored into the ESC constant (0x1B) so no raw control bytes
// ever appear in source. Ctrl+X / Tab are written as explicit unicode escapes.
/** Row 1 static keys (Esc/Tab flank the one-shot Ctrl/Alt/AltGr chips). */
internal val TOP_ROW_KEYS = listOf(
    "Esc" to ESC, "Tab" to "\u0009",
)
/** Row 1 arrows shown around the one-shot modifiers. */
internal val NAV_ARROWS = listOf(
    "<-" to ESC + "[D", "Up" to ESC + "[A", "Dn" to ESC + "[B", "->" to ESC + "[C",
)
/** Row 2: Enter + editing keys + symbols (Esc/Tab moved to row 1). */
internal val EDIT_SYMBOL_KEYS = listOf(
    "Enter" to "\r",
    "Home" to ESC + "[H", "End" to ESC + "[F", "PgUp" to ESC + "[5~", "PgDn" to ESC + "[6~",
    "Ins" to ESC + "[2~", "Del" to ESC + "[3~",
    "|" to "|", "~" to "~", "-" to "-", "_" to "_",
    "/" to "/", "\\" to "\\", ":" to ":", ";" to ";",
    "\"" to "\"", "'" to "'", "$" to "$", "&" to "&",
    "*" to "*", "=" to "=", "+" to "+", "!" to "!", "?" to "?", "#" to "#",
)
internal val FN_KEYS = listOf(
    "F1" to ESC + "OP", "F2" to ESC + "OQ", "F3" to ESC + "OR", "F4" to ESC + "OS",
    "F5" to ESC + "[15~", "F6" to ESC + "[17~", "F7" to ESC + "[18~", "F8" to ESC + "[19~",
    "F9" to ESC + "[20~", "F10" to ESC + "[21~", "F11" to ESC + "[23~", "F12" to ESC + "[24~",
)
