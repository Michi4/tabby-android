package at.websters.tabbyandroid.data.ssh

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** BMP ranges with East Asian Wide / Fullwidth display width (2 columns). */
private val WIDE_RANGES = listOf(
    0x1100 to 0x115F, // Hangul Jamo
    0x2E80 to 0x303E, // CJK radicals, Kangxi, ideographic description, CJK symbols
    0x3041 to 0x33FF, // Hiragana, Katakana, Bopomofo, Hangul compat, enclosed CJK
    0x3400 to 0x4DBF, // CJK Extension A
    0x4E00 to 0x9FFF, // CJK Unified Ideographs
    0xA000 to 0xA4CF, // Yi
    0xAC00 to 0xD7A3, // Hangul Syllables
    0xF900 to 0xFAFF, // CJK Compatibility Ideographs
    0xFE10 to 0xFE19, // Vertical forms
    0xFE30 to 0xFE4F, // CJK Compatibility Forms
    0xFF00 to 0xFF60, // Fullwidth ASCII variants
    0xFFE0 to 0xFFE6, // Fullwidth symbols
)

private val WIDE: BooleanArray by lazy {
    BooleanArray(0x10000) { c -> WIDE_RANGES.any { (s, e) -> c in s..e } }
}

/** Display width as rendered by [TerminalBuffer] (code point, not UTF-16 unit). */
internal fun terminalCodePointWidth(cp: Int): Int = when {
    cp == 0 -> 0
    cp > 0xFFFF -> 2
    cp in 0..0xFFFF && WIDE[cp] -> 2
    else -> 1
}

/**
 * VT100/xterm screen model (MIT, dependency-free).
 *
 * Covers what full-screen TUIs need (btop, tmux, vim, opencode, htop):
 * printable text, UTF-8, \n \r \b \t, SGR colors/attrs (incl. 256/truecolor
 * approx, bold/dim/underline/reverse), cursor moves (A/B/C/D/E/F/G/d/H/f),
 * erase (J/K/X with 0/1/2/3), insert/delete lines/chars (L/M/@/P), scroll
 * (S/T), scroll margins (DECSTBM r), cursor save/restore (s/u, ESC 7/8),
 * show/hide cursor + wrap + mouse/bracketed-passthrough/reporting modes
 * (DECSET/DECRST ?25/?7/?1047/?1048/?1049/?2004/mouse), alternate screen,
 * OSC/DCS swallowing, charset/mode swallowing, DSR responses (queued for the
 * connection to write back). Unknown sequences degrade, never crash.
 */
class TerminalBuffer(var cols: Int = 80, var rows: Int = 24, var maxScrollback: Int = 2000) {

    data class Cell(
        val ch: Char = ' ',
        val fg: Int = 7,
        val bold: Boolean = false,
        val bg: Int = 0,
        val reverse: Boolean = false,
        val underline: Boolean = false,
        val dim: Boolean = false,
        /** Second half of a double-width glyph (skipped in text dumps). */
        val wide2nd: Boolean = false,
    )
    data class Snapshot(
        val lines: List<List<Cell>>,
        val cursorRow: Int,
        val cursorCol: Int,
        val version: Long,
        val cursorVisible: Boolean = true,
    )

    private val main: ArrayDeque<MutableList<Cell>> = ArrayDeque()
    private val alt: ArrayDeque<MutableList<Cell>> = ArrayDeque()
    private var altActive = false
    private fun active(): ArrayDeque<MutableList<Cell>> = if (altActive) alt else main

    private var cursorRow = 0
    private var cursorCol = 0
    private var curFg = 7
    private var curBg = 0
    private var curBold = false
    private var curReverse = false
    private var curUnderline = false
    private var curDim = false
    private var cursorVisible = true
    private var wrapAround = true
    private var bracketedPasteMode = false
    /**
     * Mouse-reporting mode the server last requested (DECSET 1000/1002/1003,
     * 0 = off). 1006 only switches the ENCODING ([mouseSgr]); the mode
     * itself comes from 1000/1002/1003, like xterm.
     */
    private var mouseMode = 0
    private var mouseSgr = false

    /** True while the server has enabled DEC private mode 2004. */
    @get:Synchronized
    val bracketedPaste: Boolean get() = bracketedPasteMode

    /** Active mouse-reporting mode: 0 = off, else 1000/1002/1003. */
    @get:Synchronized
    val mouseTracking: Int get() = mouseMode

    /** True while the server requested SGR (1006) mouse encoding. */
    @get:Synchronized
    val mouseSgrEncoding: Boolean get() = mouseSgr

    /** True while the alternate screen is active (vim/opencode-style TUIs). */
    @get:Synchronized
    val altScreen: Boolean get() = altActive
    // scroll margins, 0-based inclusive, relative to the visible viewport
    private var scrollTop = 0
    private var scrollBottom = rows - 1
    // saved cursors (CSI s/u + ESC 7/8 operate on the active screen)
    private var savedRow = 0
    private var savedCol = 0
    private var savedMainRow = 0
    private var savedMainCol = 0

    private var version = 0L
    private val _updates = MutableStateFlow(0L)
    val updates: StateFlow<Long> = _updates

    /** Bytes the server asked for (DSR/CPR) — drained by the connection. */
    private val pendingOut = mutableListOf<ByteArray>()
    /** Incomplete trailing bytes (split UTF-8 / split escape) held for next feed. */
    private var carry: ByteArray = byteArrayOf()

    init {
        repeat(rows) { main.addLast(blankLine()) }
        repeat(rows) { alt.addLast(blankLine()) }
    }

    private fun blankLine(): MutableList<Cell> = MutableList(cols) { Cell() }

    private fun blankCell(): Cell = Cell()

    /** No visible content: spaces on the default background (safe to drop). */
    private fun isBlankLine(line: List<Cell>): Boolean =
        line.all { it.ch == ' ' && it.bg == 0 && !it.reverse }

    @Synchronized
    fun snapshot(): Snapshot {
        // Slice first, then copy — the deque may hold thousands of scrollback
        // lines and only the tail window is ever rendered per frame.
        val vis = active().takeLast(rows).map { it.toList() }
        return Snapshot(
            // main may hold scrollback; alt is exactly [rows]
            if (!altActive) vis.takeLast(rows) else vis,
            cursorRow, cursorCol, version, cursorVisible,
        )
    }

    /** Total lines held (live tail window + scrollback above it). */
    @Synchronized
    fun lineCount(): Int = active().size

    /**
     * Up to [max] scrollback lines sitting directly above the live tail
     * window (oldest → newest), for the scrollable history view. Empty on the
     * alt screen (exactly [rows], no scrollback) and when there is no history
     * yet. Copies only the window — never the whole deque.
     */
    @Synchronized
    fun historyWindow(max: Int): List<List<Cell>> {
        val all = active()
        val end = (all.size - rows).coerceAtLeast(0)
        val start = (end - max.coerceAtLeast(0)).coerceAtLeast(0)
        return (start until end).map { all[it].toList() }
    }

    /** DSR/CPR replies queued since the last call (connection writes them back). */
    @Synchronized
    fun takePendingOutput(): List<ByteArray> {
        if (pendingOut.isEmpty()) return emptyList()
        val out = pendingOut.toList()
        pendingOut.clear()
        return out
    }

    @Synchronized
    fun feed(data: ByteArray, off: Int = 0, len: Int = data.size) {
        val incoming = if (carry.isEmpty()) {
            data.copyOfRange(off, off + len)
        } else {
            carry + data.copyOfRange(off, off + len)
        }
        carry = byteArrayOf()
        var i = 0
        val end = incoming.size
        while (i < end) {
            val b = incoming[i].toInt() and 0xFF
            when {
                b == 0x1B && i + 1 >= end -> { carry = incoming.copyOfRange(i, end); break }
                b == 0x1B && i + 1 < end && incoming[i + 1] == '['.code.toByte() -> {
                    var j = i + 2
                    // intermediates + params (digits ; : ? $ ' " space) then final 0x40..0x7E
                    while (j < end) {
                        val c = incoming[j].toInt() and 0xFF
                        if (c in 0x40..0x7E) break
                        j++
                    }
                    if (j >= end) { carry = incoming.copyOfRange(i, end); break }
                    val cmd = incoming[j].toInt().toChar()
                    val params = incoming.slice(i + 2 until j).map { it.toInt().toChar() }.joinToString("").split(";")
                    if (cmd == 'M' && params.size == 1 && params[0].isEmpty() && mouseTracking != 0) {
                        // X10 mouse event (`ESC [ M Cb Cx Cy`, no 1006): the
                        // three bytes after M belong to the event, not the
                        // screen. A bare DL here is unrepresentable while a
                        // mouse app runs (xterm resolves the same way — this
                        // ambiguity is why SGR 1006 exists). Incomplete tail
                        // waits for the rest via carry, like any split read.
                        if (j + 3 >= end) { carry = incoming.copyOfRange(i, end); break }
                        val b1 = incoming[j + 1].toInt() and 0xFF
                        val b2 = incoming[j + 2].toInt() and 0xFF
                        val b3 = incoming[j + 3].toInt() and 0xFF
                        if (b1 in 32..255 && b2 in 32..255 && b3 in 32..255) {
                            i = j + 4
                        } else {
                            handleCsi(cmd, params)
                            i = j + 1
                        }
                    } else {
                        handleCsi(cmd, params)
                        i = j + 1
                    }
                }
                b == 0x1B && i + 1 < end && (incoming[i + 1] == 'M'.code.toByte() || incoming[i + 1] == 'c'.code.toByte()) -> {
                    if (incoming[i + 1] == 'M'.code.toByte()) reverseIndex() else reset()
                    i += 2
                }
                b == 0x1B && i + 1 < end && incoming[i + 1] == '7'.code.toByte() -> {
                    savedRow = cursorRow; savedCol = cursorCol; i += 2
                }
                b == 0x1B && i + 1 < end && incoming[i + 1] == '8'.code.toByte() -> {
                    cursorRow = savedRow.coerceIn(0, rows - 1)
                    cursorCol = savedCol.coerceIn(0, cols - 1)
                    i += 2
                }
                b == 0x1B && i + 1 < end && incoming[i + 1] == 'D'.code.toByte() -> {
                    lineFeed(); i += 2
                }
                b == 0x1B && i + 1 < end && incoming[i + 1] == 'E'.code.toByte() -> {
                    cursorCol = 0; lineFeed(); i += 2
                }
                // OSC (titles, colors, queries), APC (kitty graphics!), SOS, PM
                // and DCS (e.g. tmux-wrapped passthrough) — all end with BEL or
                // ST and never produce visible text. APC especially must be
                // swallowed: its base64 payload would otherwise splatter the
                // screen. tmux-wrapped DCS (`ESC P tmux; ...`) is unwrapped
                // (doubled ESCs collapsed) and the inner sequence is parsed.
                b == 0x1B && i + 1 < end &&
                    (incoming[i + 1] == ']'.code.toByte() || incoming[i + 1] == 'P'.code.toByte() ||
                        incoming[i + 1] == '_'.code.toByte() || incoming[i + 1] == '^'.code.toByte() ||
                        incoming[i + 1] == 'X'.code.toByte()) -> {
                    val found = findStringEnd(
                        incoming, i + 2, end,
                        tmuxAware = incoming[i + 1] == 'P'.code.toByte(),
                    )
                    if (found == null) {
                        carry = incoming.copyOfRange(i, end); break
                    }
                    found.second?.let { feed(it) }
                    i = found.first
                }
                // charset selection ESC ( B, ESC ) 0, ESC # 8, ... — never visible text
                b == 0x1B && i + 1 < end && incoming[i + 1].toInt().toChar() in "()#%*+" -> {
                    if (i + 2 >= end) { carry = incoming.copyOfRange(i, end); break }
                    i += 3
                }
                // keypad / cursor mode switches ESC = > < and save/restore ESC 7 8
                b == 0x1B && i + 1 < end && incoming[i + 1].toInt().toChar() in "=><78" -> {
                    i += 2
                }
                b == 0x1B -> i += if (i + 1 < end) 2 else 1 // ignore other escapes
                b == '\n'.code -> { lineFeed(); i++ }
                b == 0x0B || b == 0x0C -> { lineFeed(); i++ } // VT/FF = line feed
                b == '\r'.code -> { cursorCol = 0; i++ }
                b == '\b'.code || b == 0x7F -> {
                    // BS moves left; DEL (0x7F) is ignored as input echo here
                    if (b == '\b'.code && cursorCol > 0) cursorCol--
                    i++
                }
                b == '\t'.code -> { cursorCol = ((cursorCol / 8) + 1) * 8; if (cursorCol >= cols) lineFeed() else Unit; i++ }
                b == 0x07 -> i++ // BEL - could vibrate, ignored at buffer level
                b == 0x00 || b == 0x0E || b == 0x0F -> i++ // NUL/SO/SI ignored
                b < 0x20 -> i++ // other C0 controls ignored
                else -> {
                    val need = utf8Len(b)
                    if (i + need > end) { carry = incoming.copyOfRange(i, end); break }
                    val text = runCatching { String(incoming, i, need, Charsets.UTF_8) }.getOrDefault("?")
                    if (text.isEmpty() || text[0] == '\uFFFD' && need > 1 && !isValidUtf8(incoming, i, need)) {
                        putChar('?')
                    } else {
                        putText(text)
                    }
                    i += need
                }
            }
        }
        version++
        _updates.value = version
    }

    private fun endsTerminated(data: ByteArray, from: Int, end: Int): Boolean {
        var j = from
        while (j < end) {
            val c = data[j].toInt() and 0xFF
            if (c == 0x07) return true
            if (c == 0x1B && j + 1 < end && data[j + 1] == '\\'.code.toByte()) return true
            j++
        }
        return false
    }

    private fun utf8Len(b0: Int): Int = when {
        b0 < 0x80 -> 1
        b0 in 0xC2..0xDF -> 2
        b0 in 0xE0..0xEF -> 3
        b0 in 0xF0..0xF4 -> 4
        else -> 1
    }

    private fun isValidUtf8(data: ByteArray, i: Int, len: Int): Boolean {
        for (k in 1 until len) {
            if (i + k >= data.size) return false
            val c = data[i + k].toInt() and 0xFF
            if (c !in 0x80..0xBF) return false
        }
        return true
    }

    private fun viewportBase(): Int = (active().size - rows).coerceAtLeast(0)

    /** First visible deque index (UI maps absolute search hits to rows). */
    fun visibleBase(): Int = viewportBase()

    private fun physRow(logical: Int): Int = viewportBase() + logical.coerceIn(0, rows - 1)

    private fun ensureRow(@Suppress("UNUSED_PARAMETER") r: Int) {
        if (altActive) {
            while (active().size < rows) active().addLast(blankLine())
            return
        }
        while (active().size < rows) active().addLast(blankLine())
    }

    private fun putChar(ch: Char) {
        if (ch == '\u0000') return
        putCells(ch.toString(), width = 1)
    }

    /** Writes one Unicode code point (1–2 UTF-16 units) with correct column width. */
    private fun putText(s: String) {
        var k = 0
        while (k < s.length) {
            val cp = s.codePointAt(k)
            val units = Character.charCount(cp)
            if (cp == 0) {
                k += units
                continue
            }
            val w = terminalCodePointWidth(cp)
            putCells(s.substring(k, k + units), w)
            k += units
        }
    }

    private fun putCells(units: String, width: Int) {
        val w = width.coerceIn(1, 2)
        if (cursorCol + w > cols) {
            if (wrapAround) lineFeed() else cursorCol = (cols - w).coerceAtLeast(0)
        }
        if (cursorCol >= cols) {
            if (wrapAround) lineFeed() else cursorCol = (cols - w).coerceAtLeast(0)
        }
        ensureRow(cursorRow)
        val phys = physRow(cursorRow)
        if (phys in active().indices && cursorCol in 0 until cols) {
            val row = active()[phys]
            row[cursorCol] = Cell(
                units[0], curFg, curBold, curBg, curReverse, curUnderline, curDim
            )
            // wide glyphs occupy two cells: the second holds the low surrogate
            // (supplementary plane) or a blank filler (CJK). Either way the
            // concatenated line text stays correct and overwrites stay aligned.
            if (w == 2 && cursorCol + 1 < cols) {
                val second = if (units.length > 1) units[1] else ' '
                row[cursorCol + 1] = Cell(
                    second, curFg, curBold, curBg, curReverse, curUnderline, curDim,
                    wide2nd = true,
                )
            }
        }
        cursorCol += w
    }

    private fun lineFeed() = newLine()

    private fun newLine() {
        cursorCol = 0
        if (altActive) {
            if (cursorRow == scrollBottom) {
                // scroll the margin region (usually the whole alt screen)
                val base = viewportBase()
                val top = base + scrollTop.coerceIn(0, rows - 1)
                val bottom = base + scrollBottom.coerceIn(0, rows - 1)
                if (top in active().indices && bottom in active().indices && top <= bottom) {
                    for (r in top until bottom) {
                        active()[r] = active()[r + 1]
                    }
                    active()[bottom] = blankLine()
                }
            } else {
                cursorRow = (cursorRow + 1).coerceAtMost(rows - 1)
            }
            return
        }
        if (cursorRow == scrollBottom && scrollTop == 0 && scrollBottom == rows - 1) {
            // fast path: full-screen scroll with scrollback
            cursorRow = rows - 1
            active().addLast(blankLine())
            if (active().size > rows + maxScrollback) active().removeFirst()
        } else if (cursorRow == scrollBottom) {
            // scroll within margins
            val base = viewportBase()
            val top = base + scrollTop
            val bottom = base + scrollBottom
            if (top in active().indices && bottom in active().indices) {
                for (r in top until bottom) active()[r] = active()[r + 1]
                active()[bottom] = blankLine()
            }
        } else {
            cursorRow++
            if (cursorRow >= rows) {
                cursorRow = rows - 1
                active().addLast(blankLine())
                if (active().size > rows + maxScrollback) active().removeFirst()
            }
        }
        ensureRow(cursorRow)
    }

    private fun reverseIndex() {
        if (altActive) {
            if (cursorRow == scrollTop) {
                val base = viewportBase()
                val top = base + scrollTop
                val bottom = base + scrollBottom
                if (top in active().indices && bottom in active().indices) {
                    for (r in bottom downTo top + 1) active()[r] = active()[r - 1]
                    active()[top] = blankLine()
                }
            } else if (cursorRow > 0) cursorRow--
            return
        }
        if (cursorRow == scrollTop && (scrollTop != 0 || scrollBottom != rows - 1)) {
            val base = viewportBase()
            val top = base + scrollTop
            val bottom = base + scrollBottom
            if (top in active().indices && bottom in active().indices) {
                for (r in bottom downTo top + 1) active()[r] = active()[r - 1]
                active()[top] = blankLine()
            }
        } else if (cursorRow == 0 && scrollTop == 0) {
            active().addFirst(blankLine())
            if (active().size > rows + maxScrollback) active().removeLast()
        } else if (cursorRow > 0) cursorRow--
    }

    /**
     * Adapts to a new viewport size (Termius-style SIGWINCH path: the UI calls
     * this plus `setPtySize` whenever the visible area changes — font size,
     * key rows, keyboard, fullscreen, rotation). Content is preserved:
     * overlong lines truncate to the new width (like xterm on shrink),
     * short lines pad with blanks, scrollback is kept within its bound,
     * cursor/margins/saved positions clamp. No-op when unchanged.
     */
    @Synchronized
    fun resize(newCols: Int, newRows: Int) {
        val nc = newCols.coerceIn(20, 300)
        val nr = newRows.coerceIn(5, 200)
        if (nc == cols && nr == rows) return
        // Glue the cursor to its PHYSICAL line across the resize (xterm):
        // the viewport base shifts when rows change, so a blind clamp
        // teleports the cursor to another line — and the next readline
        // redraw (SIGWINCH → CPR → reprint) lands on the wrong line,
        // duplicating prompts on every keyboard toggle. Recompute the
        // screen-relative row from the pre-resize physical row instead.
        // Front trims (scrollback bound) shift physical indices down.
        val cursorPhys = viewportBase() + cursorRow
        val activeIsAlt = altActive
        var frontTrimmed = 0
        for (deque in listOf(main, alt)) {
            for (idx in deque.indices) {
                val line = deque[idx]
                // NOTE: removeAt, never removeLast(): the latter resolves to
                // Java 21's List.removeLast (absent on Android/JDK17 runtimes)
                while (line.size > nc) line.removeAt(line.lastIndex)
                while (line.size < nc) line.add(blankCell())
            }
        }
        cols = nc
        rows = nr
        while (main.size < nr) main.addLast(blankLine())
        while (alt.size < nr) alt.addLast(blankLine())
        while (alt.size > nr) {
            alt.removeFirst()
            if (activeIsAlt) frontTrimmed++
        }
        while (main.size > nr + maxScrollback) {
            main.removeFirst()
            if (!activeIsAlt) frontTrimmed++
        }
        // Drop trailing blank FILLER on the main screen (fresh-shell padding
        // from ensureRow): a shrink must not slide the window past the cursor
        // line when only blanks sit below it — the cursor would leave the
        // visible window and the next redraw would strand a duplicate prompt.
        // Never touches the cursor's own line or non-blank content; blanks
        // re-pad on the next grow.
        if (!activeIsAlt) {
            val physAdj = cursorPhys - frontTrimmed
            // Trim while the window starts below the cursor line: each
            // dropped filler row moves the window up one toward the cursor.
            while (main.size > nr && main.size - nr > physAdj &&
                main.size - 1 != physAdj && isBlankLine(main.last())
            ) {
                // NOTE: removeAt, never removeLast() (see above).
                main.removeAt(main.lastIndex)
            }
        }
        cursorRow = (cursorPhys - frontTrimmed - viewportBase()).coerceIn(0, nr - 1)
        cursorCol = cursorCol.coerceIn(0, nc - 1)
        scrollTop = 0
        scrollBottom = nr - 1
        savedRow = savedRow.coerceIn(0, nr - 1)
        savedCol = savedCol.coerceIn(0, nc - 1)
        savedMainRow = savedMainRow.coerceIn(0, nr - 1)
        savedMainCol = savedMainCol.coerceIn(0, nc - 1)
        version++
        _updates.value = version
    }

    @Synchronized
    fun reset() {
        main.clear()
        repeat(rows) { main.addLast(blankLine()) }
        alt.clear()
        repeat(rows) { alt.addLast(blankLine()) }
        altActive = false
        cursorRow = 0; cursorCol = 0
        curFg = 7; curBg = 0; curBold = false
        curReverse = false; curUnderline = false; curDim = false
        cursorVisible = true; wrapAround = true; bracketedPasteMode = false
        mouseMode = 0; mouseSgr = false
        scrollTop = 0; scrollBottom = rows - 1
        savedRow = 0; savedCol = 0
        version++; _updates.value = version
    }

    /**
     * Finds the end of an OSC/APC/SOS/PM/DCS string starting at [from].
     * Returns (index after terminator, tmux-unwrapped inner bytes or null),
     * or null when the chunk ends mid-string (caller carries the tail).
     * With [tmuxAware] (DCS only), a `tmux;` prefix switches to tmux rules:
     * doubled ESCs collapse to one and the inner sequence is returned for
     * parsing; the terminator is ST (single ESC + backslash) or BEL.
     */
    private fun findStringEnd(
        data: ByteArray,
        from: Int,
        end: Int,
        tmuxAware: Boolean,
    ): Pair<Int, ByteArray?>? {
        var tmux = false
        if (tmuxAware && end - from >= 5 &&
            data[from] == 't'.code.toByte() && data[from + 1] == 'm'.code.toByte() &&
            data[from + 2] == 'u'.code.toByte() && data[from + 3] == 'x'.code.toByte() &&
            data[from + 4] == ';'.code.toByte()
        ) {
            tmux = true
        }
        val inner = if (tmux) ByteArrayOutputStream() else null
        var j = from + if (tmux) 5 else 0
        while (j < end) {
            val c = data[j].toInt() and 0xFF
            if (c == 0x07) {
                return (j + 1) to inner?.toByteArray()
            }
            if (c == 0x1B) {
                if (j + 1 >= end) return null // split terminator — wait for more
                val n = data[j + 1]
                if (n == '\\'.code.toByte()) {
                    return (j + 2) to inner?.toByteArray()
                }
                if (tmux && n == 0x1B.toByte()) {
                    inner?.write(0x1B)
                    j += 2
                    continue
                }
                // unexpected ESC inside a plain string: stop before it so the
                // outer loop parses it normally (never swallow real escapes)
                if (!tmux) return j to null
            }
            if (tmux) inner?.write(c)
            j++
        }
        return null
    }

    /** Approximates 256-color / truecolor down to the 8-color screen model. */
    private fun approxChan(r: Int, g: Int, b: Int): Int {
        val mx = maxOf(r, g, b)
        if (mx <= 0) return 0
        if (r == mx && g == mx && b == mx) return 7
        if (r == mx && g == mx) return 3
        if (r == mx && b == mx) return 5
        if (g == mx && b == mx) return 6
        return if (r == mx) 1 else if (g == mx) 2 else 4
    }

    private fun approx256(idx: Int): Int = when {
        idx in 0..7 -> idx
        idx in 8..15 -> idx - 8
        idx in 16..231 -> {
            val v = idx - 16
            approxChan(v / 36, (v % 36) / 6, v % 6)
        }
        idx >= 244 -> 7
        else -> 0
    }

    private fun approxRgb(r: Int, g: Int, b: Int): Int =
        approxChan(r / 51, g / 51, b / 51)

    private fun enterAlt(clear: Boolean, saveCursor: Boolean) {
        if (saveCursor) { savedMainRow = cursorRow; savedMainCol = cursorCol }
        if (!altActive) {
            altActive = true
            while (alt.size < rows) alt.addLast(blankLine())
            while (alt.size > rows) alt.removeFirst()
        }
        scrollTop = 0; scrollBottom = rows - 1
        if (clear) {
            for (r in alt.indices) alt[r] = blankLine()
            cursorRow = 0; cursorCol = 0
        }
    }

    private fun exitAlt(restoreCursor: Boolean) {
        if (!altActive) return
        altActive = false
        scrollTop = 0; scrollBottom = rows - 1
        if (restoreCursor) {
            cursorRow = savedMainRow.coerceIn(0, rows - 1)
            cursorCol = savedMainCol.coerceIn(0, cols - 1)
        }
    }

    private fun handleCsi(cmd: Char, params: List<String>) {
        val private = params.firstOrNull()?.startsWith("?") == true
        // Intermediate bytes change the meaning (`<` SGR mouse echoes,
        // `>`/`=` device queries, `$`/`"`/`'`/` ` DECRQM-style requests…).
        // We only implement plain + `?`-private sequences: anything else is
        // swallowed, NEVER executed as its final letter — an echoed mouse
        // click (`CSI < … M`) must not delete lines like `M` (DL) would.
        if (params.any { p -> p.any { c -> c == '<' || c == '>' || c == '=' || c == '$' || c == '"' || c == '\'' || c == ' ' } }) return
        fun p(i: Int, def: Int): Int = params.getOrNull(i)?.filter { it.isDigit() }?.toIntOrNull() ?: def
        fun allNums(): List<Int> = params.mapNotNull { it.filter { c -> c.isDigit() }.toIntOrNull() }
        when (cmd) {
            'A' -> cursorRow = (cursorRow - p(0, 1).coerceAtLeast(1)).coerceAtLeast(0)
            'B', 'e' -> cursorRow = (cursorRow + p(0, 1).coerceAtLeast(1)).coerceAtMost(rows - 1)
            'C' -> cursorCol = (cursorCol + p(0, 1).coerceAtLeast(1)).coerceAtMost(cols - 1)
            'D' -> cursorCol = (cursorCol - p(0, 1).coerceAtLeast(1)).coerceAtLeast(0)
            'E' -> { cursorRow = (cursorRow + p(0, 1).coerceAtLeast(1)).coerceAtMost(rows - 1); cursorCol = 0 }
            'F' -> { cursorRow = (cursorRow - p(0, 1).coerceAtLeast(1)).coerceAtLeast(0); cursorCol = 0 }
            'H', 'f' -> {
                cursorRow = (p(0, 1) - 1).coerceIn(0, rows - 1)
                cursorCol = (p(1, 1) - 1).coerceIn(0, cols - 1)
            }
            'G', '`' -> cursorCol = (p(0, 1) - 1).coerceIn(0, cols - 1)
            'd' -> cursorRow = (p(0, 1) - 1).coerceIn(0, rows - 1)
            'J' -> {
                val n = p(0, 0)
                val base = viewportBase()
                when (n) {
                    3 -> {
                        // clear scrollback, keep viewport
                        if (!altActive) {
                            while (active().size > rows) active().removeFirst()
                        }
                    }
                    2 -> {
                        for (r in 0 until rows) {
                            val phys = base + r
                            if (phys in active().indices) active()[phys] = blankLine()
                        }
                    }
                    1 -> {
                        for (r in 0..cursorRow) {
                            val phys = base + r
                            if (phys !in active().indices) continue
                            if (r < cursorRow) active()[phys] = blankLine()
                            else for (c in 0..cursorCol.coerceIn(0, cols - 1)) active()[phys][c] = blankCell()
                        }
                    }
                    else -> {
                        for (r in cursorRow until rows) {
                            val phys = base + r
                            if (phys !in active().indices) continue
                            if (r > cursorRow) active()[phys] = blankLine()
                            else for (c in cursorCol.coerceIn(0, cols - 1) until cols) active()[phys][c] = blankCell()
                        }
                    }
                }
            }
            'K' -> {
                val phys = physRow(cursorRow)
                if (phys in active().indices) {
                    when (p(0, 0)) {
                        1 -> for (c in 0..cursorCol.coerceIn(0, cols - 1)) active()[phys][c] = blankCell()
                        2 -> active()[phys] = blankLine()
                        else -> for (c in cursorCol.coerceIn(0, cols - 1) until cols) active()[phys][c] = blankCell()
                    }
                }
            }
            'X' -> {
                val n = p(0, 1).coerceIn(1, cols)
                val phys = physRow(cursorRow)
                if (phys in active().indices) {
                    for (c in cursorCol.coerceIn(0, cols - 1) until (cursorCol + n).coerceAtMost(cols)) {
                        active()[phys][c] = blankCell()
                    }
                }
            }
            '@' -> {
                val n = p(0, 1).coerceIn(1, cols)
                val phys = physRow(cursorRow)
                if (phys in active().indices) {
                    for (c in (cols - 1) downTo cursorCol) {
                        active()[phys][c] = if (c - n >= cursorCol) active()[phys][c - n] else blankCell()
                    }
                }
            }
            'P' -> {
                val n = p(0, 1).coerceIn(1, cols)
                val phys = physRow(cursorRow)
                if (phys in active().indices) {
                    for (c in cursorCol until cols) {
                        active()[phys][c] = if (c + n < cols) active()[phys][c + n] else blankCell()
                    }
                }
            }
            'L' -> {
                val n = p(0, 1).coerceIn(1, rows)
                val base = viewportBase()
                val top = (base + cursorRow).coerceIn(base, base + scrollBottom)
                val bottom = base + scrollBottom
                repeat(n) {
                    if (top in active().indices && bottom in active().indices && top <= bottom) {
                        for (r in bottom downTo top + 1) active()[r] = active()[r - 1]
                        active()[top] = blankLine()
                    }
                }
            }
            'M' -> {
                val n = p(0, 1).coerceIn(1, rows)
                val base = viewportBase()
                val top = (base + cursorRow).coerceIn(base, base + scrollBottom)
                val bottom = base + scrollBottom
                repeat(n) {
                    if (top in active().indices && bottom in active().indices && top <= bottom) {
                        for (r in top until bottom) active()[r] = active()[r + 1]
                        active()[bottom] = blankLine()
                    }
                }
            }
            'S' -> {
                val n = p(0, 1).coerceIn(1, rows)
                val base = viewportBase()
                repeat(n) {
                    val bottom = base + scrollBottom
                    val top = base + scrollTop
                    if (top in active().indices && bottom in active().indices) {
                        for (r in top until bottom) active()[r] = active()[r + 1]
                        active()[bottom] = blankLine()
                    }
                }
            }
            'T' -> {
                val n = p(0, 1).coerceIn(1, rows)
                val base = viewportBase()
                repeat(n) {
                    val bottom = base + scrollBottom
                    val top = base + scrollTop
                    if (top in active().indices && bottom in active().indices) {
                        for (r in bottom downTo top + 1) active()[r] = active()[r - 1]
                        active()[top] = blankLine()
                    }
                }
            }
            'r' -> {
                val top = (p(0, 1) - 1).coerceIn(0, rows - 1)
                val bottom = (p(1, rows) - 1).coerceIn(0, rows - 1)
                if (top < bottom) {
                    scrollTop = top; scrollBottom = bottom
                    cursorRow = 0; cursorCol = 0
                } else {
                    scrollTop = 0; scrollBottom = rows - 1
                }
            }
            's' -> { savedRow = cursorRow; savedCol = cursorCol }
            'u' -> {
                cursorRow = savedRow.coerceIn(0, rows - 1)
                cursorCol = savedCol.coerceIn(0, cols - 1)
            }
            'h', 'l' -> {
                val on = cmd == 'h'
                for (n in allNums()) {
                    when (n) {
                        7 -> wrapAround = on
                        25 -> cursorVisible = on
                        1047 -> if (on) enterAlt(clear = false, saveCursor = false) else exitAlt(restoreCursor = false)
                        1048 -> if (on) {
                            savedMainRow = cursorRow; savedMainCol = cursorCol
                            savedRow = cursorRow; savedCol = cursorCol
                        } else {
                            cursorRow = savedMainRow.coerceIn(0, rows - 1)
                            cursorCol = savedMainCol.coerceIn(0, cols - 1)
                        }
                        1049 -> if (on) enterAlt(clear = true, saveCursor = true) else exitAlt(restoreCursor = true)
                        2004 -> if (private) bracketedPasteMode = on
                        // mouse tracking: 1000 press-only, 1002 button-event,
                        // 1003 any-event (mode); 1006 switches SGR encoding.
                        1000, 1002, 1003 -> if (private) {
                            mouseMode = if (on) n else if (mouseMode == n) 0 else mouseMode
                        }
                        1006 -> if (private) mouseSgr = on
                        // legacy 1005/1010/1015/1016 encodings (folded into
                        // SGR/X10 above), 12 cursor blink, 1 cursor keys —
                        // accepted, no-op.
                        1005, 1010, 1015, 1016, 12, 1 -> Unit
                        else -> Unit
                    }
                }
                if (private && allNums().isEmpty()) Unit
            }
            'n' -> {
                when (p(0, 0)) {
                    5 -> pendingOut.add("\u001B[0n".toByteArray(Charsets.UTF_8))
                    6 -> pendingOut.add("\u001B[${cursorRow + 1};${cursorCol + 1}R".toByteArray(Charsets.UTF_8))
                }
            }
            'c' -> Unit // device attributes query — swallow (assume VT100)
            'm' -> {
                val raw = params.flatMap { it.split(":") }.mapNotNull { it.toIntOrNull() }
                if (raw.isEmpty()) {
                    curFg = 7; curBg = 0; curBold = false
                    curReverse = false; curUnderline = false; curDim = false
                }
                var k = 0
                while (k < raw.size) {
                    val n = raw[k]
                    when {
                        n == 0 -> {
                            curFg = 7; curBg = 0; curBold = false
                            curReverse = false; curUnderline = false; curDim = false; k++
                        }
                        n == 1 -> { curBold = true; k++ }
                        n == 2 -> { curDim = true; k++ }
                        n == 3 -> k++ // italic — accepted, no visual
                        n == 4 -> { curUnderline = true; k++ }
                        n == 7 -> { curReverse = true; k++ }
                        n == 22 -> { curBold = false; curDim = false; k++ }
                        n == 23 -> k++
                        n == 24 -> { curUnderline = false; k++ }
                        n == 27 -> { curReverse = false; k++ }
                        n in 30..37 -> { curFg = n - 30; k++ }
                        n in 90..97 -> { curFg = n - 90; k++ }
                        n in 40..47 -> { curBg = n - 40; k++ }
                        n in 100..107 -> { curBg = n - 100; k++ }
                        n == 39 -> { curFg = 7; k++ }
                        n == 49 -> { curBg = 0; k++ }
                        (n == 38 || n == 48) && k + 2 < raw.size && raw[k + 1] == 5 -> {
                            val c = approx256(raw[k + 2])
                            if (n == 38) curFg = c else curBg = c
                            k += 3
                        }
                        (n == 38 || n == 48) && k + 4 < raw.size && raw[k + 1] == 2 -> {
                            val c = approxRgb(raw[k + 2], raw[k + 3], raw[k + 4])
                            if (n == 38) curFg = c else curBg = c
                            k += 5
                        }
                        else -> k++
                    }
                }
            }
        }
    }

    /** Plain text of one line (wide second-halves skipped, like the display). */
    private fun lineText(line: List<Cell>): String =
        line.filter { !it.wide2nd }.joinToString("") { it.ch.toString() }

    /** Changes the scrollback cap (Settings), trimming oldest lines at once. */
    @Synchronized
    fun updateMaxScrollback(n: Int) {
        maxScrollback = n.coerceIn(1000, 50000)
        while (main.size > rows + maxScrollback) main.removeAt(0)
        version++
        _updates.value = version
    }

    /**
     * Absolute deque indices of lines containing [query] (case-insensitive).
     * Pure search for the find bar; UI subtracts [visibleBase] for rows.
     */
    @Synchronized
    fun searchLines(query: String): List<Int> {
        if (query.isEmpty()) return emptyList()
        return active().mapIndexedNotNull { i, line ->
            if (lineText(line).contains(query, ignoreCase = true)) i else null
        }
    }

    /** Last [n] main-screen lines as plain text (for tab-restore seeding). */
    @Synchronized
    fun lastLines(n: Int): List<String> =
        main.takeLast(n.coerceIn(1, 500)).map { lineText(it).trimEnd() }

    /** Plain-text dump of visible screen (for tests / accessibility / search). */
    @Synchronized
    fun visibleText(): String {
        val base = viewportBase()
        return (0 until rows).joinToString("\n") { r ->
            active().getOrNull(base + r)?.let { lineText(it).trimEnd() }.orEmpty()
        }.trimEnd()
    }
}
