package at.websters.tabbyandroid.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the scrollback-view mapping: absolute buffer indices must map to the
 * first rendered line (history window + live screen) so find jumps and
 * highlights land on the right row.
 */
class TerminalHistoryTest {

    @Test fun baseIsLiveTailWithoutHistory() {
        // 40 held, 14-row screen, no history shown → base = 26
        assertEquals(26, historyViewBase(40, 14, 0))
    }

    @Test fun baseShiftsUpByShownHistory() {
        // same buffer, 100 history lines shown → first rendered = absolute 0
        // (clamped, since only 26 exist above the tail)
        assertEquals(0, historyViewBase(40, 14, 100))
        // exactly 26 shown → base 0, no clamp needed
        assertEquals(0, historyViewBase(40, 14, 26))
        // 10 shown → base 16
        assertEquals(16, historyViewBase(40, 14, 10))
    }

    @Test fun baseNeverNegative() {
        assertEquals(0, historyViewBase(5, 14, 0))
        assertEquals(0, historyViewBase(0, 14, 0))
    }
}
