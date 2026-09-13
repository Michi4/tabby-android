package at.websters.tabbyandroid.data.local

import org.junit.Assert.*
import org.junit.Test

class UiPrefsTest {
    @Test fun defaults() {
        assertEquals(13, UiPrefs().fontSize)
        assertEquals(true, UiPrefs().follow)
        assertEquals(3, UiPrefs().keyRows)
        assertEquals(false, UiPrefs().fullscreen)
    }

    @Test fun fontClamped() {
        // practically unlimited: only anti-crash bounds remain
        assertEquals(1, sanitizeFontSize(0))
        assertEquals(1, sanitizeFontSize(-50))
        assertEquals(256, sanitizeFontSize(999))
        assertEquals(15, sanitizeFontSize(15))
        assertEquals(48, sanitizeFontSize(48))
        assertEquals(200, sanitizeFontSize(200))
    }

    @Test fun rowsClamped() {
        assertEquals(0, sanitizeKeyRows(-1))
        assertEquals(3, sanitizeKeyRows(7))
        assertEquals(2, sanitizeKeyRows(2))
    }
}
