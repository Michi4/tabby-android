package at.websters.tabbyandroid.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the extended-keyboard byte sequences: no raw control bytes may appear
 * in source (ESC factored into a constant), and every key must send the exact
 * VT100/xterm sequence. Fails loudly if key maps ever get mangled again.
 */
class TerminalKeysTest {

    private fun bytes(s: String): List<Int> = s.toByteArray(Charsets.UTF_8).map { it.toInt() and 0xFF }

    @Test fun escIsSingleEscapeByte() {
        assertEquals(listOf(0x1B), bytes(ESC))
    }

    @Test fun noRawControlBytesInLabels() {
        val all = SYMBOL_KEYS + NAV_KEYS + FN_KEYS
        assertTrue(all.isNotEmpty())
        for ((label, _) in all) {
            for (b in bytes(label)) {
                assertTrue("label '$label' contains control byte $b", b >= 0x20 || b == 0x0A)
            }
        }
    }

    @Test fun noRedundantComboRows() {
        // combos live in the CTRL/ALT toggles + keyboard; key rows stay clean
        val all = SYMBOL_KEYS + NAV_KEYS + FN_KEYS
        assertTrue(all.none { (label, _) -> label.startsWith("Ctrl+") })
    }

    @Test fun symbolKeys() {
        val m = SYMBOL_KEYS.toMap()
        assertEquals(listOf(0x1B), bytes(m.getValue("Esc")))
        assertEquals(listOf(0x09), bytes(m.getValue("Tab")))
        assertEquals(listOf('|'.code), bytes(m.getValue("|")))
        assertEquals(listOf('\\'.code), bytes(m.getValue("\\")))
    }

    @Test fun navKeysAreCsiSequences() {
        val m = NAV_KEYS.toMap()
        assertEquals("\u001B[A", m.getValue("Up"))
        assertEquals("\u001B[D", m.getValue("<-"))
        assertEquals("\u001B[5~", m.getValue("PgUp"))
        assertEquals("\u001B[H", m.getValue("Home"))
    }

    @Test fun functionKeys() {
        val m = FN_KEYS.toMap()
        assertEquals(12, m.size)
        assertEquals("\u001BOP", m.getValue("F1"))
        assertEquals("\u001B[24~", m.getValue("F12"))
    }

    @Test fun togglePathBytes() {
        // CTRL/ALT toggles + keyboard replace the old combo rows; the byte
        // mappings themselves are guarded in CtrlKeysTest
        assertEquals("\u001B[C", NAV_KEYS.toMap().getValue("->"))
    }
}
