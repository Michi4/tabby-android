package at.websters.tabbyandroid.data.ssh

import org.junit.Assert.*
import org.junit.Test

/**
 * Terminal sender bar: every commit maps to SSH bytes exactly once.
 * `old` is always the sentinel-only field; `new` is the keyboard commit.
 */
class SenderEditTest {
    private val s = SENDER_SENTINEL

    @Test fun sentinelIsZeroWidth() {
        assertEquals("\uFEFF", SENDER_SENTINEL)
    }

    @Test fun noop() {
        assertEquals(SenderEdit("", 0), senderEdit(s, s))
    }

    @Test fun typeOneChar() {
        assertEquals(SenderEdit("x", 0), senderEdit(s, s + "x"))
    }

    @Test fun typeBatch() {
        assertEquals(SenderEdit("touch /tmp/a", 0), senderEdit(s, s + "touch /tmp/a"))
    }

    @Test fun softBackspace() {
        // sentinel deleted -> exactly one DEL over SSH
        assertEquals(SenderEdit("", 1), senderEdit(s, ""))
    }

    @Test fun deleteTypedCharSendsOneDel() {
        // defensive: if the field ever holds a typed char, removing it = DEL
        assertEquals(SenderEdit("", 1), senderEdit(s + "a", s))
        assertEquals(SenderEdit("", 0), senderEdit(s, s))
    }

    @Test fun newlinePassesThrough() {
        // multi-line paste: caller maps \n to \r per char
        assertEquals(SenderEdit("a\nb", 0), senderEdit(s, s + "a\nb"))
    }

    @Test fun sentinelNeverSent() {
        // a commit that somehow re-contains the sentinel still sends no trace of it
        val edit = senderEdit(s, s + "a" + s)
        assertFalse(edit.sendText.contains(SENDER_SENTINEL))
        assertTrue(edit.sendText.contains("a"))
    }

    @Test fun fullReplacementSendsDelPlusText() {
        // keyboard nuked the sentinel (shouldn't happen with suggestions off);
        // worst case is one spurious DEL, text itself intact
        val edit = senderEdit(s, "xyz")
        assertEquals(1, edit.deletions)
        assertEquals("xyz", edit.sendText)
    }
}
