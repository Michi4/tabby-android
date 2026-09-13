package at.websters.tabbyandroid.data.ssh

import org.junit.Assert.assertEquals
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

    @Test fun bracketedPasteToggleIsSwallowed() {
        val b = TerminalBuffer(cols = 40, rows = 5)
        b.feed("$ESC[?2004hx".toByteArray())
        assertTrue(b.visibleText().contains("x"))
        assertTrue(!b.visibleText().contains("?"))
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
}
