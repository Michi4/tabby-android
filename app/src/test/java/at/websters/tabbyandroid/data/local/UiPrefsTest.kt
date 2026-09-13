package at.websters.tabbyandroid.data.local

import org.junit.Assert.*
import org.junit.Test

class UiPrefsTest {
    @Test fun defaults() {
        assertEquals(13, UiPrefs().fontSize)
        assertEquals(true, UiPrefs().follow)
        assertEquals(3, UiPrefs().keyRows)
    }

    @Test fun fontClamped() {
        assertEquals(10, sanitizeFontSize(1))
        assertEquals(20, sanitizeFontSize(99))
        assertEquals(15, sanitizeFontSize(15))
    }

    @Test fun rowsClamped() {
        assertEquals(0, sanitizeKeyRows(-1))
        assertEquals(3, sanitizeKeyRows(7))
        assertEquals(2, sanitizeKeyRows(2))
    }
}
