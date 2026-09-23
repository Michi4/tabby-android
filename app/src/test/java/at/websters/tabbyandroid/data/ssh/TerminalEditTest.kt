package at.websters.tabbyandroid.data.ssh

import org.junit.Assert.*
import org.junit.Test

class TerminalEditTest {
    @Test fun typing() {
        assertEquals(EditOp(0, "x"), diffEdit("ab", "abx"))
    }

    @Test fun backspace() {
        assertEquals(EditOp(1, ""), diffEdit("ab", "a"))
        assertEquals(EditOp(3, ""), diffEdit("abc", ""))
    }

    @Test fun identical() {
        assertEquals(EditOp(0, ""), diffEdit("ab", "ab"))
    }

    @Test fun midLineEdit() {
        val op = diffEdit("ac", "abc")
        assertEquals(0, op.deletions)
        assertEquals("b", op.added)
    }

    @Test fun replace() {
        val op = diffEdit("hello", "hallo")
        assertEquals(1, op.deletions)
        assertEquals("a", op.added)
    }

    @Test fun pasteBatch() {
        val op = diffEdit("$ ", "$ echo hi")
        assertEquals(0, op.deletions)
        assertEquals("echo hi", op.added)
    }

    @Test fun emptyOld() {
        assertEquals(EditOp(0, "ls"), diffEdit("", "ls"))
    }

    @Test fun supplementaryCodePointAddsWholePair() {
        assertEquals(EditOp(0, "\uD83D\uDE00"), diffEdit("a", "a\uD83D\uDE00"))
    }

    @Test fun supplementaryCodePointDeletesOnce() {
        assertEquals(EditOp(1, ""), diffEdit("a\uD83D\uDE00b", "ab"))
    }

    @Test fun wideAndSupplementaryReplacementsCountCodePoints() {
        assertEquals(EditOp(1, "\uD83D\uDE00"), diffEdit("a中b", "a\uD83D\uDE00b"))
        assertEquals(EditOp(1, "中"), diffEdit("a\uD83D\uDE00b", "a中b"))
    }

    @Test fun swipeRightSendsRightArrows() {
        // Gboard spacebar-swipe: same text, cursor glides right.
        assertEquals("\u001B[C\u001B[C\u001B[C", cursorMoveArrows("\uFEFFabc", 1, "\uFEFFabc", 4))
    }

    @Test fun swipeLeftSendsLeftArrows() {
        assertEquals("\u001B[D\u001B[D", cursorMoveArrows("\uFEFFabc", 4, "\uFEFFabc", 2))
    }

    @Test fun noMoveSendsNothing() {
        assertNull(cursorMoveArrows("\uFEFFabc", 2, "\uFEFFabc", 2))
    }

    @Test fun textChangeNeverMapsToArrows() {
        assertNull(cursorMoveArrows("\uFEFFabc", 1, "\uFEFFabcd", 4))
    }

    @Test fun negativePositionsAreIgnored() {
        assertNull(cursorMoveArrows("\uFEFFabc", -1, "\uFEFFabc", 2))
    }
}
