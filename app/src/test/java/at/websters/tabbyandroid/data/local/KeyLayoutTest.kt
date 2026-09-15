package at.websters.tabbyandroid.data.local

import org.junit.Assert.*
import org.junit.Test

class KeyLayoutTest {
    @Test fun seqMappings() {
        assertEquals("\u001B", keySeqFor("esc"))
        assertEquals("\u0009", keySeqFor("tab"))
        assertEquals("\r", keySeqFor("enter"))
        assertEquals("\u001B[A", keySeqFor("up"))
        assertEquals("\u001B[D", keySeqFor("left"))
        assertEquals("\u001B[3~", keySeqFor("del"))
        assertEquals("\u001BOP", keySeqFor("f1"))
        assertEquals("\u001B[24~", keySeqFor("f12"))
        assertEquals("|", keySeqFor("|"))
        assertEquals(null, keySeqFor("ctrl"))
        assertEquals(null, keySeqFor("alt"))
        assertEquals(null, keySeqFor("altgr"))
        assertEquals(null, keySeqFor("nope"))
    }

    @Test fun defaultLayoutResolves() {
        val known = allKeyIds().toSet()
        for (row in defaultKeyRows()) {
            for (id in row) {
                assertTrue("default id unknown: $id", id in known)
                if (id !in MODIFIER_KEY_IDS) {
                    assertTrue("default id has no seq: $id", keySeqFor(id) != null)
                }
            }
        }
    }

    @Test fun sanitizeDropsUnknownAndDupes() {
        val out = sanitizeKeyLayout(KeyLayout(listOf(listOf("esc", "bogus", "esc", "up")), 4))
        assertEquals(listOf(listOf("esc", "up")), out.rows)
        assertEquals(4, out.spacingDp)
    }

    @Test fun sanitizeFallsBackAndClamps() {
        assertEquals(
            KeyLayout(defaultKeyRows(), 16),
            sanitizeKeyLayout(KeyLayout(listOf(listOf("bogus")), 99)),
        )
        assertEquals(0, sanitizeKeyLayout(KeyLayout(spacingDp = -5)).spacingDp)
        assertEquals(16, sanitizeKeyLayout(KeyLayout(spacingDp = 99)).spacingDp)
        val five = List(5) { listOf("esc") }
        assertEquals(4, sanitizeKeyLayout(KeyLayout(five, 4)).rows.size)
    }

    @Test fun nextKeyRowsCyclesLayoutAware() {
        // 3-row layout: 3 -> 2 -> 1 -> hidden -> 3
        assertEquals(2, nextKeyRows(3, 3))
        assertEquals(1, nextKeyRows(2, 3))
        assertEquals(0, nextKeyRows(1, 3))
        assertEquals(3, nextKeyRows(0, 3))
        // 4-row custom layout reaches the 4th row; 1-row layout toggles 1 <-> hidden
        assertEquals(4, nextKeyRows(0, 4))
        assertEquals(3, nextKeyRows(4, 4))
        assertEquals(1, nextKeyRows(0, 1))
        assertEquals(0, nextKeyRows(1, 1))
        // degenerate inputs clamp instead of crashing
        assertEquals(1, nextKeyRows(0, 0))
        assertEquals(4, nextKeyRows(0, 99))
        assertEquals(0, nextKeyRows(1, 0))
    }

    @Test fun labels() {
        assertEquals("Esc", keyLabelFor("esc"))
        assertEquals("Up", keyLabelFor("up"))
        assertEquals("Dn", keyLabelFor("down"))
        assertEquals("AltGr", keyLabelFor("altgr"))
        assertEquals("|", keyLabelFor("|"))
    }
}
