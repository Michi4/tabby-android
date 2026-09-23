package at.websters.tabbyandroid.data.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalBufferTest {
    @Test fun plainText() {
        val b = TerminalBuffer(cols = 20, rows = 5)
        b.feed("hello".toByteArray())
        assertTrue(b.visibleText().contains("hello"))
    }

    @Test fun newlineAndCarriageReturn() {
        val b = TerminalBuffer(cols = 20, rows = 5)
        b.feed("a\r\nb".toByteArray())
        val t = b.visibleText()
        assertTrue(t.contains("a"))
        assertTrue(t.contains("b"))
    }

    @Test fun sgrColorDoesNotCrashAndKeepsText() {
        val b = TerminalBuffer(cols = 40, rows = 5)
        b.feed("\u001B[32mgreen\u001B[0m normal".toByteArray())
        val t = b.visibleText()
        assertTrue(t.contains("green"))
        assertTrue(t.contains("normal"))
        val snap = b.snapshot()
        assertEquals(7, snap.lines[0][13].fg) // after reset -> default fg
    }

    @Test fun clearScreen() {
        val b = TerminalBuffer(cols = 20, rows = 5)
        b.feed("hello".toByteArray())
        b.feed("\u001B[2J".toByteArray())
        assertEquals("", b.visibleText())
    }

    @Test fun cursorHome() {
        val b = TerminalBuffer(cols = 10, rows = 5)
        b.feed("abcdef\u001B[1;1HXY".toByteArray())
        assertTrue(b.visibleText().startsWith("XY"))
    }

    private val ESC = 27.toChar()
    private val BEL = 7.toChar()

    @Test fun oscTitleIsSwallowed() {
        val b = TerminalBuffer(cols = 40, rows = 5)
        b.feed("$ESC]0;mytitle${BEL}hello".toByteArray())
        val t = b.visibleText()
        assertTrue(t.contains("hello"))
        assertTrue(!t.contains("mytitle"))
    }

    @Test fun bracketedPasteToggleIsTracked() {
        val b = TerminalBuffer(cols = 40, rows = 5)
        assertFalse(b.bracketedPaste)
        b.feed("$ESC[?2004hx".toByteArray())
        assertTrue(b.bracketedPaste)
        assertTrue(b.visibleText().contains("x"))
        assertTrue(!b.visibleText().contains("?"))
        b.feed("$ESC[?2004l".toByteArray())
        assertFalse(b.bracketedPaste)
    }

    @Test fun charsetSelectIsSwallowed() {
        val b = TerminalBuffer(cols = 40, rows = 5)
        b.feed("$ESC(B${ESC})0y".toByteArray())
        assertEquals("y", b.visibleText())
    }

    @Test fun sgr256DoesNotLeak() {
        val b = TerminalBuffer(cols = 40, rows = 5)
        b.feed("$ESC[38;5;196mRED${ESC}[0m".toByteArray())
        val t = b.visibleText()
        assertTrue(t.contains("RED"))
        assertTrue(!t.contains("38"))
        val snap = b.snapshot()
        assertEquals(1, snap.lines[0][0].fg) // 196 -> red approx
    }

    @Test fun sgrTruecolorDoesNotLeak() {
        val b = TerminalBuffer(cols = 40, rows = 5)
        b.feed("$ESC[38;2;0;255;0mGREEN".toByteArray())
        val t = b.visibleText()
        assertTrue(t.contains("GREEN"))
        assertEquals(2, b.snapshot().lines[0][0].fg) // green approx
    }

    @Test fun dcsIsSwallowed() {
        val b = TerminalBuffer(cols = 40, rows = 5)
        b.feed(("${ESC}P1\$r0q$BEL" + "z").toByteArray())
        assertEquals("z", b.visibleText())
    }

    @Test fun apcKittyGraphicsIsSwallowed() {
        val b = TerminalBuffer(cols = 40, rows = 5)
        b.feed(("${ESC}_Gi=31337,s=1,v=1,a=q,t=d,f=24;AAAA${ESC}\\" + "z").toByteArray())
        assertEquals("z", b.visibleText())
    }

    @Test fun sosAndPmAreSwallowed() {
        val b = TerminalBuffer(cols = 40, rows = 5)
        b.feed(("${ESC}Xignored${ESC}\\" + "${ESC}^ignored$BEL" + "y").toByteArray())
        assertEquals("y", b.visibleText())
    }

    @Test fun tmuxWrappedDcsIsUnwrapped() {
        val b = TerminalBuffer(cols = 40, rows = 5)
        // tmux doubles inner ESCs: ESC P tmux; ESC ESC [1;1H X ESC \
        b.feed(("${ESC}Ptmux;${ESC}${ESC}[1;1HX${ESC}\\" + "y").toByteArray())
        val t = b.visibleText()
        assertTrue(t.startsWith("X"))
        assertTrue(t.contains("y"))
    }

    @Test fun altScreenEnterExit() {
        val b = TerminalBuffer(cols = 20, rows = 4)
        b.feed("main".toByteArray())
        b.feed("$ESC[?1049h".toByteArray())
        assertEquals("", b.visibleText())
        b.feed("alt".toByteArray())
        assertTrue(b.visibleText().contains("alt"))
        b.feed("$ESC[?1049l".toByteArray())
        assertTrue(b.visibleText().contains("main"))
        assertTrue(!b.visibleText().contains("alt"))
    }

    @Test fun altScreen1047Switch() {
        val b = TerminalBuffer(cols = 20, rows = 4)
        b.feed("main".toByteArray())
        b.feed("$ESC[?1047h".toByteArray())
        b.feed("x".toByteArray())
        b.feed("$ESC[?1047l".toByteArray())
        assertTrue(b.visibleText().contains("main"))
    }

    @Test fun eraseLineVariants() {
        val b = TerminalBuffer(cols = 10, rows = 3)
        b.feed("abcdef".toByteArray())
        b.feed("$ESC[1;1H$ESC[2K".toByteArray())
        assertEquals("", b.visibleText())
    }

    @Test fun eraseDisplayFromCursor() {
        val b = TerminalBuffer(cols = 10, rows = 4)
        b.feed("a\r\nb\r\nc".toByteArray())
        b.feed("$ESC[2;1H$ESC[0J".toByteArray())
        val t = b.visibleText()
        assertTrue(t.contains("a"))
        assertTrue(!t.contains("b"))
        assertTrue(!t.contains("c"))
    }

    @Test fun insertDeleteLines() {
        val b = TerminalBuffer(cols = 10, rows = 4)
        b.feed("1\r\n2\r\n3".toByteArray())
        b.feed("$ESC[1;1H$ESC[1L".toByteArray())
        val t = b.visibleText()
        assertTrue(t.contains("1"))
        assertTrue(t.contains("2"))
    }

    @Test fun cursorSaveRestore() {
        val b = TerminalBuffer(cols = 10, rows = 4)
        b.feed("ab$ESC[sXY$ESC[uZ".toByteArray())
        assertTrue(b.visibleText().contains("Z"))
    }

    @Test fun cursorHideShow() {
        val b = TerminalBuffer(cols = 10, rows = 3)
        b.feed("$ESC[?25lx".toByteArray())
        assertEquals(false, b.snapshot().cursorVisible)
        b.feed("$ESC[?25h".toByteArray())
        assertEquals(true, b.snapshot().cursorVisible)
    }

    @Test fun dsrCursorReportQueued() {
        val b = TerminalBuffer(cols = 80, rows = 24)
        b.feed("$ESC[6n".toByteArray())
        val out = b.takePendingOutput()
        assertEquals(1, out.size)
        assertTrue(String(out[0], Charsets.UTF_8).endsWith("R"))
    }

    @Test fun dsrStatusQueued() {
        val b = TerminalBuffer(cols = 80, rows = 24)
        b.feed("$ESC[5n".toByteArray())
        val out = b.takePendingOutput()
        assertEquals("\u001B[0n", String(out[0], Charsets.UTF_8))
    }

    @Test fun reverseSgrStored() {
        val b = TerminalBuffer(cols = 20, rows = 3)
        b.feed("$ESC[7mR$ESC[27mN".toByteArray())
        val snap = b.snapshot()
        assertEquals(true, snap.lines[0][0].reverse)
        assertEquals(false, snap.lines[0][1].reverse)
    }

    @Test fun scrollMarginsSet() {
        val b = TerminalBuffer(cols = 20, rows = 5)
        b.feed("$ESC[2;4r".toByteArray())
        b.feed("$ESC[r".toByteArray())
        assertTrue(b.visibleText().isEmpty())
    }

    @Test fun utf8SplitAcrossFeeds() {
        val b = TerminalBuffer(cols = 20, rows = 3)
        val bytes = "ä".toByteArray(Charsets.UTF_8)
        assertEquals(2, bytes.size)
        b.feed(bytes.copyOfRange(0, 1))
        b.feed(bytes.copyOfRange(1, 2))
        assertTrue(b.visibleText().contains("ä"))
    }

    @Test fun mouseModeSwallowed() {
        val b = TerminalBuffer(cols = 40, rows = 5)
        b.feed("$ESC[?1000hq".toByteArray())
        assertEquals("q", b.visibleText())
    }

    @Test fun cjkAdvancesTwoColumns() {
        val b = TerminalBuffer(cols = 20, rows = 3)
        b.feed("A中B".toByteArray(Charsets.UTF_8))
        assertEquals(4, b.snapshot().cursorCol)
        assertTrue(b.visibleText().contains("A中B"))
    }

    @Test fun cjkOverwriteStaysAligned() {
        val b = TerminalBuffer(cols = 20, rows = 3)
        b.feed("12中文34\r--".toByteArray(Charsets.UTF_8))
        assertTrue(b.visibleText().startsWith("--中文34"))
    }

    @Test fun emojiPairAdvancesTwoColumns() {
        val b = TerminalBuffer(cols = 20, rows = 3)
        b.feed("A😀B".toByteArray(Charsets.UTF_8))
        assertEquals(4, b.snapshot().cursorCol)
        assertTrue(b.visibleText().contains("B"))
    }

    @Test fun cjkWrapsAtMargin() {
        val b = TerminalBuffer(cols = 3, rows = 4)
        b.feed("中文中".toByteArray(Charsets.UTF_8))
        // a wide glyph that doesn't fit wraps whole (like xterm)
        assertEquals("中\n文\n中", b.visibleText())
    }

    @Test fun boxDrawingStillOneColumn() {
        val b = TerminalBuffer(cols = 20, rows = 3)
        b.feed("─│┌⣀█".toByteArray(Charsets.UTF_8))
        assertEquals(5, b.snapshot().cursorCol)
    }

    @Test fun searchFindsLines() {
        val b = TerminalBuffer(cols = 20, rows = 5)
        b.feed("hello world\r\nfoo\r\nHELLO again".toByteArray())
        assertEquals(listOf(0, 2), b.searchLines("hello"))
        assertEquals(listOf(1), b.searchLines("FOO"))
        assertTrue(b.searchLines("").isEmpty())
        assertTrue(b.searchLines("zzz").isEmpty())
    }

    @Test fun lastLinesDumpsTail() {
        val b = TerminalBuffer(cols = 20, rows = 5)
        b.feed("one\r\ntwo\r\nthree".toByteArray())
        assertEquals(listOf("one", "two", "three", "", ""), b.lastLines(5))
        assertEquals(listOf("", ""), b.lastLines(2).map { it })
    }

    @Test fun maxScrollbackTrims() {
        val b = TerminalBuffer(cols = 20, rows = 5, maxScrollback = 2000)
        repeat(5) { b.feed("x\r\n".toByteArray()) }
        b.updateMaxScrollback(1000)
        assertEquals(1000, b.maxScrollback)
        b.updateMaxScrollback(1) // clamped, never below 1000
        assertEquals(1000, b.maxScrollback)
    }

    @Test fun resizeNoopKeepsVersion() {
        val b = TerminalBuffer(cols = 20, rows = 5)
        b.feed("hi".toByteArray())
        val v = b.snapshot().version
        b.resize(20, 5)
        assertEquals(v, b.snapshot().version)
    }

    @Test fun resizeGrowKeepsContentAndCursor() {
        val b = TerminalBuffer(cols = 20, rows = 5)
        b.feed("hi".toByteArray())
        b.resize(100, 30)
        assertTrue(b.visibleText().contains("hi"))
        assertEquals(2, b.snapshot().cursorCol)
        assertEquals(100, b.snapshot().lines[0].size)
    }

    @Test fun resizeShrinkTruncatesAndClamps() {
        // note: resize clamps to 20..300 x 5..200, so shrink within bounds
        val b = TerminalBuffer(cols = 40, rows = 8)
        repeat(7) { b.feed("\r\n".toByteArray()) } // content lands on the last row
        b.feed("0123456789ABCDEFGHIJKLMNOPQRSTUV".toByteArray())
        b.resize(24, 5)
        val snap = b.snapshot()
        assertTrue(snap.lines.all { it.size == 24 })
        assertTrue(snap.cursorCol <= 23)
        assertTrue(snap.cursorRow <= 4)
        assertTrue(b.visibleText().contains("0123456789ABCDEFGHIJKLMN"))
    }

    @Test fun resizeClampsToSaneBounds() {
        val b = TerminalBuffer(cols = 20, rows = 8)
        b.resize(1, 1) // clamped to minimums, never zero/negative
        assertEquals(20, b.cols)
        assertEquals(5, b.rows)
    }

    @Test fun resizeResetsMargins() {
        val b = TerminalBuffer(cols = 20, rows = 5)
        b.feed("$ESC[2;4r".toByteArray())
        b.resize(40, 10)
        // margins reset: full-screen scroll works again
        b.feed("x".toByteArray())
        assertTrue(b.visibleText().contains("x"))
    }

    @Test fun resizeBoundsScrollback() {
        val b = TerminalBuffer(cols = 20, rows = 5, maxScrollback = 2)
        repeat(10) { b.feed("l$it\r\n".toByteArray()) }
        b.resize(20, 4)
        assertTrue(b.snapshot().lines.size <= 4 + 2)
    }

    @Test fun snapshotIsTailOnly() {
        val b = TerminalBuffer(cols = 20, rows = 5)
        repeat(12) { b.feed("l$it\r\n".toByteArray()) }
        val snap = b.snapshot()
        assertEquals(5, snap.lines.size)
        // 5 initial rows + 8 full-screen scrolls = 13 held; tail is l8..l11 + blank
        assertEquals(13, b.lineCount())
        val texts = snap.lines.map { line ->
            line.filter { !it.wide2nd }.joinToString("") { it.ch.toString() }.trim()
        }
        assertTrue(texts.any { it.startsWith("l11") })
    }

    @Test fun historyWindowSitsAboveTail() {
        val b = TerminalBuffer(cols = 20, rows = 5)
        repeat(12) { b.feed("l$it\r\n".toByteArray()) }
        // 13 held, tail is 5 → 8 above ([l0..l7]); window(4) = [l4..l7]
        val win = b.historyWindow(4)
        assertEquals(4, win.size)
        fun text(line: List<TerminalBuffer.Cell>) =
            line.filter { !it.wide2nd }.joinToString("") { it.ch.toString() }.trim()
        assertTrue(text(win.first()).startsWith("l4"))
        assertTrue(text(win.last()).startsWith("l7"))
        // full history when max exceeds available
        assertEquals(8, b.historyWindow(500).size)
    }

    @Test fun historyWindowEmptyWithoutHistory() {
        val b = TerminalBuffer(cols = 20, rows = 5)
        b.feed("hi\r\n".toByteArray())
        assertTrue(b.historyWindow(100).isEmpty())
        // alt screen holds exactly [rows] → no history either
        b.feed("$ESC[?1049h".toByteArray())
        b.feed("alt\r\n".toByteArray())
        assertTrue(b.historyWindow(100).isEmpty())
        assertEquals(5, b.snapshot().lines.size)
    }

    @Test fun resizeKeepsCursorGluedToPhysicalLine() {
        // Keyboard-toggle shrink: the cursor must stay on the SAME content
        // line (blind clamping teleports it, and the next readline redraw
        // then duplicates the prompt — the green-circle bug).
        val b = TerminalBuffer(cols = 20, rows = 10)
        for (i in 1..7) b.feed("line$i\n".toByteArray())
        b.feed("line8".toByteArray())
        fun cursorLine(s: TerminalBuffer.Snapshot): String =
            s.lines[s.cursorRow].joinToString("") { it.ch.toString() }.trim()
        val before = b.snapshot()
        assertEquals("line8", cursorLine(before))
        val physBefore = b.visibleBase() + before.cursorRow
        b.resize(20, 5)
        val shrunk = b.snapshot()
        assertEquals("line8", cursorLine(shrunk))
        assertEquals(physBefore, b.visibleBase() + shrunk.cursorRow)
        b.resize(20, 10)
        val grown = b.snapshot()
        assertEquals("line8", cursorLine(grown))
        assertEquals(physBefore, b.visibleBase() + grown.cursorRow)
    }

    @Test fun echoedSgrMouseNeverDeletesLines() {
        // Our own tap/wheel bytes come back via pty echo; the SGR sequences
        // must be swallowed, never executed as DL (M) — or every click would
        // eat screen lines.
        val b = TerminalBuffer(cols = 20, rows = 6)
        b.feed("aaa\r\nbbb\r\nccc\r\n".toByteArray())
        val before = b.visibleText()
        b.feed("\u001B[<0;5;3M\u001B[<3;5;3m".toByteArray()) // tap press+release
        b.feed("\u001B[<64;5;3M".toByteArray()) // wheel up
        b.feed("\u001B[<65;5;3M".toByteArray()) // wheel down
        assertEquals(before, b.visibleText())
    }

    @Test fun x10MouseBytesConsumedWhileTracking() {
        // Legacy encoding (`ESC [ M Cb Cx Cy`) while 1000 (no 1006) is on:
        // consumed silently, never DL + literal splatter.
        val b = TerminalBuffer(cols = 20, rows = 6)
        b.feed("aaa\r\nbbb\r\nccc\r\n".toByteArray())
        b.feed("\u001B[?1000h".toByteArray())
        val before = b.visibleText()
        b.feed("\u001B[M \u0025#".toByteArray()) // press button 0 at 5,3
        b.feed("\u001B[M#%#".toByteArray()) // release at 5,3
        assertEquals(before, b.visibleText())
    }

    @Test fun splitX10MouseReassembles() {
        val b = TerminalBuffer(cols = 20, rows = 6)
        b.feed("aaa\r\nbbb\r\nccc\r\n".toByteArray())
        b.feed("\u001B[?1000h".toByteArray())
        val before = b.visibleText()
        b.feed("\u001B[M ".toByteArray()) // partial: M + 1 of 3 bytes
        assertEquals(before, b.visibleText())
        b.feed("\u0025#".toByteArray()) // remainder
        assertEquals(before, b.visibleText())
    }

    @Test fun bareDeleteLineStillWorks() {        // No mouse tracking: `ESC[M` is DL as before (regression guard for
        // the X10 special case), and DECSET still applies (gate guard).
        val b = TerminalBuffer(cols = 20, rows = 6)
        b.feed("aaa\r\nbbb\r\nccc".toByteArray())
        b.feed("\u001B[2;1H".toByteArray()) // cursor to row 2 = "bbb" (1-based)
        b.feed("\u001B[M".toByteArray()) // delete 1 line
        val vis = b.visibleText()
        assertTrue("bbb should be gone: $vis", !vis.lines().any { it.trim() == "bbb" })
        assertTrue("ccc should survive: $vis", vis.lines().any { it.trim() == "ccc" })
        val c = TerminalBuffer(cols = 20, rows = 6)
        c.feed("\u001B[?25l".toByteArray())
        assertTrue(!c.snapshot().cursorVisible)
        c.feed("\u001B[?25h".toByteArray())
        assertTrue(c.snapshot().cursorVisible)
    }

    @Test(timeout = 30_000) fun binaryGarbageNeverCrashes() {
        // Fixed-seed fuzz over the full byte range incl. split feeds:
        // hostile/malformed server output must degrade, never throw or hang.
        val rnd = java.util.Random(0x5EED1234)
        val b = TerminalBuffer(cols = 51, rows = 24)
        val chunk = ByteArray(4096)
        repeat(40) {
            rnd.nextBytes(chunk)
            // Feed in odd splits to exercise the carry path on every shape.
            var off = 0
            while (off < chunk.size) {
                val n = 1 + rnd.nextInt(700)
                b.feed(chunk, off, minOf(n, chunk.size - off))
                off += n
            }
            // Every snapshot operation must stay consistent.
            val s = b.snapshot()
            assertTrue(s.cursorRow in 0 until 24)
            assertTrue(s.cursorCol in 0 until 51)
            b.visibleText()
            b.lastLines(50)
            b.searchLines("x")
            b.historyWindow(100)
        }
        b.resize(30, 12)
        b.reset()
        assertEquals(12, b.snapshot().lines.size)
    }
}
