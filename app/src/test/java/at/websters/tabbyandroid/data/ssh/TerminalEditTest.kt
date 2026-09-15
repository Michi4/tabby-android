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
}
