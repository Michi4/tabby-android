package at.websters.tabbyandroid.data.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoShellTest {
    private fun proc(r: DemoInputResult): String = String(r.procBytes, Charsets.UTF_8)
    private fun echo(r: DemoInputResult): String = String(r.echo, Charsets.UTF_8)

    @Test fun typingEchoesAndAppends() {
        val d = DemoLineDiscipline()
        val r = d.input("hi")
        assertEquals("", proc(r))
        assertEquals("hi", echo(r))
        assertEquals("hi", d.currentLine)
        assertFalse(r.eof)
    }

    @Test fun enterSubmitsLineWithNewline() {
        val d = DemoLineDiscipline()
        d.input("echo hi")
        val r = d.input("\r")
        assertEquals("echo hi\n", proc(r))
        assertEquals("\r\n$ ", echo(r))
        assertEquals("", d.currentLine)
    }

    @Test fun backspaceErasesLocally() {
        val d = DemoLineDiscipline()
        d.input("ab")
        val r = d.input(127.toChar().toString())
        assertEquals("", proc(r))
        assertEquals("\b \b", echo(r))
        assertEquals("a", d.currentLine)
    }

    @Test fun backspaceOnEmptyLineIsNoop() {
        val d = DemoLineDiscipline()
        val r = d.input(127.toChar().toString())
        assertEquals("", proc(r))
        assertEquals("", echo(r))
    }

    @Test fun ctrlCClearsLine() {
        val d = DemoLineDiscipline()
        d.input("rm -rf")
        val r = d.input(3.toChar().toString())
        assertEquals("", proc(r))
        assertEquals("^C\r\n$ ", echo(r))
        assertEquals("", d.currentLine)
    }

    @Test fun ctrlDOnEmptyLineSignalsEof() {
        val d = DemoLineDiscipline()
        val r = d.input(4.toChar().toString())
        assertTrue(r.eof)
    }

    @Test fun ctrlDWithPendingLineIsIgnored() {
        val d = DemoLineDiscipline()
        d.input("ab")
        val r = d.input(4.toChar().toString())
        assertFalse(r.eof)
        assertEquals("ab", d.currentLine)
    }

    @Test fun backspaceErasesWideCjk() {
        val d = DemoLineDiscipline()
        val typed = d.input("中")
        assertEquals("中", echo(typed))
        val del = d.input(127.toChar().toString())
        assertEquals("\b\b  \b\b", echo(del))
        assertEquals("", d.currentLine)
    }

    @Test fun backspaceErasesWholeSurrogateCodePoint() {
        val d = DemoLineDiscipline()
        d.input("\uD83D\uDE00")
        val del = d.input(127.toChar().toString())
        assertEquals("\b\b  \b\b", echo(del))
        assertEquals("", d.currentLine)
    }

    @Test fun splitSurrogateBuffersUntilComplete() {
        val d = DemoLineDiscipline()
        val high = d.input("\uD83D")
        assertEquals("", echo(high))
        val low = d.input("\uDE00")
        assertEquals("\uD83D\uDE00", echo(low))
        assertEquals("\uD83D\uDE00", d.currentLine)
    }

    @Test fun backspaceCancelsPendingSurrogate() {
        val d = DemoLineDiscipline()
        d.input("\uD83D")
        val del = d.input(127.toChar().toString())
        assertEquals("", echo(del))
        assertEquals("", d.currentLine)
    }

    @Test fun pasteWithNewlineSubmits() {
        val d = DemoLineDiscipline()
        val r = d.input("ls\n")
        assertEquals("ls\n", proc(r))
        assertEquals("ls\r\n$ ", echo(r))
    }

    @Test fun controlBytesSwallowed() {
        val d = DemoLineDiscipline()
        val r = d.input("a" + 1.toChar() + "b")
        assertEquals("ab", d.currentLine)
        assertEquals("ab", echo(r))
    }

    @Test fun demoProfileIsMarked() {
        val p = demoShellProfile()
        assertTrue(p.id.startsWith(DEMO_SHELL_PREFIX))
        assertEquals("Demo shell", p.name)
    }
}
