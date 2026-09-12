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
}
