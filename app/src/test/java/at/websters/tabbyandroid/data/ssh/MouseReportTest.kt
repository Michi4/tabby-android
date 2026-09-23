package at.websters.tabbyandroid.data.ssh

import org.junit.Assert.*
import org.junit.Test

class MouseReportTest {

    @Test fun sgrTapIsPressPlusRelease() {
        assertEquals("\u001B[<0;10;5M\u001B[<3;10;5m", MouseReport.tap(10, 5, sgr = true))
    }

    @Test fun x10TapEncodesPlus32() {
        // ESC [ M, button 0 -> ' '(32), col 10 -> '*' (42), row 5 -> '%' (37);
        // release is a button-3 press ('#' = 35).
        assertEquals("\u001B[M *%\u001B[M#*%", MouseReport.tap(10, 5, sgr = false))
    }

    @Test fun sgrWheelUpIsPressOnly() {
        assertEquals("\u001B[<64;10;5M", MouseReport.wheel(up = true, col1 = 10, row1 = 5, sgr = true))
        assertEquals("\u001B[<65;10;5M", MouseReport.wheel(up = false, col1 = 10, row1 = 5, sgr = true))
    }

    @Test fun coordsClampToOne() {
        assertEquals("\u001B[<0;1;1M\u001B[<3;1;1m", MouseReport.tap(0, -3, sgr = true))
    }

    @Test fun x10CoordsCapAt223() {
        val seq = MouseReport.press(0, 1000, 1000, sgr = false)
        assertEquals(6, seq.length)
        assertEquals(255.toChar(), seq[4])
        assertEquals(255.toChar(), seq[5])
    }

    @Test fun bufferTracksMouseModes() {
        val b = TerminalBuffer(cols = 80, rows = 24)
        assertEquals(0, b.mouseTracking)
        b.feed("\u001B[?1000h".toByteArray())
        assertEquals(1000, b.mouseTracking)
        b.feed("\u001B[?1006h".toByteArray())
        assertEquals(1000, b.mouseTracking)
        assertTrue(b.mouseSgrEncoding)
        b.feed("\u001B[?1000l".toByteArray())
        assertEquals(0, b.mouseTracking)
        assertTrue(b.mouseSgrEncoding) // 1006 stays until explicitly reset
        b.feed("\u001B[?1006l".toByteArray())
        assertTrue(!b.mouseSgrEncoding)
    }

    @Test fun bufferTracksButtonEventMode() {
        val b = TerminalBuffer(cols = 80, rows = 24)
        b.feed("\u001B[?1002h".toByteArray())
        assertEquals(1002, b.mouseTracking)
    }

    @Test fun resetClearsMouseState() {
        val b = TerminalBuffer(cols = 80, rows = 24)
        b.feed("\u001B[?1000h\u001B[?1006h".toByteArray())
        b.reset()
        assertEquals(0, b.mouseTracking)
        assertTrue(!b.mouseSgrEncoding)
    }
}
