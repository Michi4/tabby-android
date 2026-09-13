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
        val all = TOP_ROW_KEYS + NAV_ARROWS + EDIT_SYMBOL_KEYS + FN_KEYS
        assertTrue(all.isNotEmpty())
        for ((label, _) in all) {
            for (b in bytes(label)) {
                assertTrue("label '$label' contains control byte $b", b >= 0x20 || b == 0x0A)
            }
        }
    }

    @Test fun noRedundantComboRows() {
        // combos live in the one-shot Ctrl/Alt/AltGr chips + keyboard; key rows stay clean
        val all = TOP_ROW_KEYS + NAV_ARROWS + EDIT_SYMBOL_KEYS + FN_KEYS
        assertTrue(all.none { (label, _) -> label.startsWith("Ctrl+") })
    }

    @Test fun symbolKeys() {
        val top = TOP_ROW_KEYS.toMap()
        assertEquals(listOf(0x1B), bytes(top.getValue("Esc")))
        assertEquals(listOf(0x09), bytes(top.getValue("Tab")))
        val m = EDIT_SYMBOL_KEYS.toMap()
        assertEquals(listOf('|'.code), bytes(m.getValue("|")))
        assertEquals(listOf('\\'.code), bytes(m.getValue("\\")))
    }

    @Test fun navKeysAreCsiSequences() {
        val m = NAV_ARROWS.toMap() + EDIT_SYMBOL_KEYS.toMap()
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
        // one-shot modifiers + keyboard replace the old combo rows; the byte
        // mappings themselves are guarded in CtrlKeysTest
        assertEquals("\u001B[C", NAV_ARROWS.toMap().getValue("->"))
    }

    @Test fun topRowHasEssentials() {
        // row 1: Esc Tab arrows live alongside one-shot Ctrl/Alt/AltGr chips
        // (chips are UI-only toggles; the static seqs below must stay exact)
        val arrows = NAV_ARROWS.toMap()
        assertEquals("\u001B[A", arrows.getValue("Up"))
        assertEquals("\u001B[D", arrows.getValue("<-"))
        val edit = EDIT_SYMBOL_KEYS.toMap()
        assertEquals("\r", edit.getValue("Enter"))
        assertEquals("\u001B[H", edit.getValue("Home"))
        assertEquals("|", edit.getValue("|"))
    }

    @Test fun editRowHasNoEscTabDupes() {
        // Esc/Tab moved to row 1; row 2 must not duplicate them
        val labels = EDIT_SYMBOL_KEYS.map { it.first }
        assertTrue(!labels.contains("Esc"))
        assertTrue(!labels.contains("Tab"))
    }

    @Test fun modModeCycles() {
        assertEquals(ModMode.ONE_SHOT, nextModMode(ModMode.OFF))
        assertEquals(ModMode.LOCKED, nextModMode(ModMode.ONE_SHOT))
        assertEquals(ModMode.OFF, nextModMode(ModMode.LOCKED))
    }
}
