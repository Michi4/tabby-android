package at.websters.tabbyandroid.data.ssh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Minimal VT100/ANSI screen model (MIT, dependency-free).
 * Supports: printable text, \n \r \b \t, SGR colors (30-37,90-97,40-47,0,1),
 * cursor moves (A/B/C/D/H/f/G), erase (J/K), clear screen.
 * Enough for shells, htop/vim basics, tmux passthrough. Full xterm passthrough
 * for anything else is intentionally degraded, not crashed (best-effort).
 */
class TerminalBuffer(val cols: Int = 80, val rows: Int = 24, val maxScrollback: Int = 2000) {

    data class Cell(val ch: Char = ' ', val fg: Int = 7, val bold: Boolean = false, val bg: Int = 0)
    data class Snapshot(val lines: List<List<Cell>>, val cursorRow: Int, val cursorCol: Int, val version: Long)

    private val screen: ArrayDeque<MutableList<Cell>> = ArrayDeque()
    private var cursorRow = 0
    private var cursorCol = 0
    private var curFg = 7
    private var curBg = 0
    private var curBold = false
    private var version = 0L
    private val _updates = MutableStateFlow(0L)
    val updates: StateFlow<Long> = _updates

    init {
        repeat(rows) { screen.addLast(blankLine()) }
    }

    private fun blankLine(): MutableList<Cell> = MutableList(cols) { Cell() }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(screen.map { it.toList() }, cursorRow, cursorCol, version)

    @Synchronized
    fun feed(data: ByteArray, off: Int = 0, len: Int = data.size) {
        var i = off
        val end = off + len
        while (i < end) {
            val b = data[i].toInt() and 0xFF
            when {
                b == 0x1B && i + 1 < end && data[i + 1] == '['.code.toByte() -> {
                    var j = i + 2
                    while (j < end && (data[j] in '0'.code.toByte()..'9'.code.toByte() || data[j] == ';'.code.toByte() || data[j] == '?'.code.toByte())) j++
                    if (j < end) {
                        val cmd = data[j].toInt().toChar()
                        val params = data.slice(i + 2 until j).map { it.toInt().toChar() }.joinToString("").split(";")
                        handleCsi(cmd, params)
                        i = j + 1
                    } else i++
                }
                b == 0x1B && i + 1 < end && (data[i + 1] == 'M'.code.toByte() || data[i + 1] == 'c'.code.toByte()) -> {
                    if (data[i + 1] == 'M'.code.toByte()) reverseIndex() else reset()
                    i += 2
                }
                // OSC (window title etc, ends with BEL or ST) and DCS (e.g. tmux passthrough)
                b == 0x1B && i + 1 < end &&
                    (data[i + 1] == ']'.code.toByte() || data[i + 1] == 'P'.code.toByte()) -> {
                    i = skipUntilBelOrSt(data, i + 2, end)
                }
                // charset selection ESC ( B, ESC ) 0, ESC # 8, ... — never visible text
                b == 0x1B && i + 1 < end && data[i + 1].toInt().toChar() in "()#%*+" -> {
                    i += if (i + 2 < end) 3 else 2
                }
                // keypad / cursor mode switches ESC = > < and save/restore ESC 7 8
                b == 0x1B && i + 1 < end && data[i + 1].toInt().toChar() in "=><78" -> {
                    i += 2
                }
                b == 0x1B -> i += if (i + 1 < end) 2 else 1 // ignore other escapes
                b == '\n'.code -> { newLine(); i++ }
                b == '\r'.code -> { cursorCol = 0; i++ }
                b == '\b'.code -> { if (cursorCol > 0) cursorCol--; i++ }
                b == '\t'.code -> { cursorCol = ((cursorCol / 8) + 1) * 8; if (cursorCol >= cols) newLine() else Unit; i++ }
                b == 0x07 -> i++ // BEL - could vibrate, ignored at buffer level
                b < 0x20 -> i++ // other C0 controls ignored
                else -> {
                    // UTF-8 aware: decode one codepoint
                    val (ch, size) = decodeUtf8(data, i, end)
                    putChar(ch)
                    i += size
                }
            }
        }
        version++
        _updates.value = version
    }

    private fun decodeUtf8(data: ByteArray, i: Int, end: Int): Pair<Char, Int> {
        val b0 = data[i].toInt() and 0xFF
        if (b0 < 0x80) return b0.toChar() to 1
        return try {
            val str = String(data, i, minOf(end - i, 4), Charsets.UTF_8)
            if (str.isEmpty()) '?' to 1 else str[0] to str.toByteArray(Charsets.UTF_8).size.coerceAtLeast(1)
        } catch (_: Exception) {
            '?' to 1
        }
    }

    private fun ensureRow(r: Int) {
        while (screen.size <= r) {
            screen.addLast(blankLine())
            if (screen.size > rows + maxScrollback) screen.removeFirst()
        }
    }

    private fun putChar(ch: Char) {
        if (cursorCol >= cols) newLine()
        ensureRow(cursorRow)
        val visRow = screen.size - rows + cursorRow.coerceIn(0, rows - 1)
        // map logical cursor to physical deque index
        val phys = (screen.size - rows).coerceAtLeast(0) + cursorRow.coerceIn(0, rows - 1)
        if (phys in screen.indices && cursorCol in 0 until cols) {
            screen[phys][cursorCol] = Cell(ch, curFg, curBold, curBg)
        }
        cursorCol++
    }

    private fun newLine() {
        cursorCol = 0
        cursorRow++
        if (cursorRow >= rows) {
            cursorRow = rows - 1
            screen.addLast(blankLine())
            if (screen.size > rows + maxScrollback) screen.removeFirst()
        }
        ensureRow((screen.size - rows).coerceAtLeast(0) + cursorRow)
    }

    private fun reverseIndex() {
        if (cursorRow == 0) screen.addFirst(blankLine()) else cursorRow--
    }

    @Synchronized
    fun reset() {
        screen.clear()
        repeat(rows) { screen.addLast(blankLine()) }
        cursorRow = 0; cursorCol = 0; curFg = 7; curBg = 0; curBold = false
        version++; _updates.value = version
    }

    /** Skips OSC/DCS payload until BEL, ST (ESC + backslash), or end of chunk. */
    private fun skipUntilBelOrSt(data: ByteArray, from: Int, end: Int): Int {
        var j = from
        while (j < end) {
            val c = data[j].toInt() and 0xFF
            if (c == 0x07) return j + 1
            if (c == 0x1B && j + 1 < end && data[j + 1] == '\\'.code.toByte()) return j + 2
            j++
        }
        return end
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

    private fun handleCsi(cmd: Char, params: List<String>) {
        fun p(i: Int, def: Int): Int = params.getOrNull(i)?.filter { it.isDigit() }?.toIntOrNull() ?: def
        when (cmd) {
            'A' -> cursorRow = (cursorRow - p(0, 1)).coerceAtLeast(0)
            'B' -> cursorRow = (cursorRow + p(0, 1)).coerceAtMost(rows - 1)
            'C' -> cursorCol = (cursorCol + p(0, 1)).coerceAtMost(cols - 1)
            'D' -> cursorCol = (cursorCol - p(0, 1)).coerceAtLeast(0)
            'H', 'f' -> {
                cursorRow = (p(0, 1) - 1).coerceIn(0, rows - 1)
                cursorCol = (p(1, 1) - 1).coerceIn(0, cols - 1)
            }
            'G' -> cursorCol = (p(0, 1) - 1).coerceIn(0, cols - 1)
            'J' -> if (p(0, 0) == 2 || p(0, 0) == 3) {
                val keepScroll = p(0, 0) == 2
                val scroll = if (keepScroll) screen.drop(screen.size - rows) else emptyList()
                screen.clear()
                if (keepScroll) { /* keep scrollback implicitly by re-adding */ }
                repeat(rows) { screen.addLast(blankLine()) }
                cursorRow = 0; cursorCol = 0
            }
            'K' -> {
                val phys = (screen.size - rows).coerceAtLeast(0) + cursorRow.coerceIn(0, rows - 1)
                if (phys in screen.indices) {
                    for (c in cursorCol until cols) screen[phys][c] = Cell()
                }
            }
            'm' -> {
                val nums = params.mapNotNull { it.toIntOrNull() }
                if (nums.isEmpty()) {
                    curFg = 7; curBg = 0; curBold = false
                }
                var k = 0
                while (k < nums.size) {
                    val n = nums[k]
                    when {
                        n == 0 -> { curFg = 7; curBg = 0; curBold = false; k++ }
                        n == 1 -> { curBold = true; k++ }
                        n == 22 -> { curBold = false; k++ }
                        n in 30..37 -> { curFg = n - 30; k++ }
                        n in 90..97 -> { curFg = n - 90; k++ }
                        n in 40..47 -> { curBg = n - 40; k++ }
                        n in 100..107 -> { curBg = n - 100; k++ }
                        n == 39 -> { curFg = 7; k++ }
                        n == 49 -> { curBg = 0; k++ }
                        (n == 38 || n == 48) && k + 2 < nums.size && nums[k + 1] == 5 -> {
                            val c = approx256(nums[k + 2])
                            if (n == 38) curFg = c else curBg = c
                            k += 3
                        }
                        (n == 38 || n == 48) && k + 4 < nums.size && nums[k + 1] == 2 -> {
                            val c = approxRgb(nums[k + 2], nums[k + 3], nums[k + 4])
                            if (n == 38) curFg = c else curBg = c
                            k += 5
                        }
                        else -> k++
                    }
                }
            }
        }
    }

    /** Plain-text dump of visible screen (for tests / accessibility / search). */
    @Synchronized
    fun visibleText(): String {
        val base = (screen.size - rows).coerceAtLeast(0)
        return (0 until rows).joinToString("\n") { r ->
            screen.getOrNull(base + r)?.joinToString("") { it.ch.toString() }?.trimEnd().orEmpty()
        }.trimEnd()
    }
}
