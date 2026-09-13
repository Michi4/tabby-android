package at.websters.tabbyandroid.data.ssh

import org.junit.Assert.*
import org.junit.Test

class CtrlKeysTest {
    @Test fun ctrlLetters() {
        assertEquals(1, CtrlKeys.ctrlByte('A')!!.toInt())
        assertEquals(1, CtrlKeys.ctrlByte('a')!!.toInt())
        assertEquals(3, CtrlKeys.ctrlByte('C')!!.toInt())
        assertEquals(4, CtrlKeys.ctrlByte('d')!!.toInt())
        assertEquals(26, CtrlKeys.ctrlByte('Z')!!.toInt())
    }

    @Test fun ctrlSymbols() {
        assertEquals(0, CtrlKeys.ctrlByte(' ')!!.toInt())
        assertEquals(27, CtrlKeys.ctrlByte('[')!!.toInt())
        assertEquals(31, CtrlKeys.ctrlByte('_')!!.toInt())
        assertEquals(127, CtrlKeys.ctrlByte('?')!!.toInt())
        assertNull(CtrlKeys.ctrlByte('1'))
        assertNull(CtrlKeys.ctrlByte('!'))
    }

    @Test fun altIsEscPrefix() {
        val seq = CtrlKeys.altSeq('x')
        assertEquals(2, seq.length)
        assertEquals(27, seq[0].code)
        assertEquals('x', seq[1])
    }

    @Test fun byteStringRoundTrip() {
        for (b in listOf(1, 3, 4, 26, 27, 127)) {
            val s = CtrlKeys.byteString(b.toByte())
            assertEquals(1, s.length)
            assertEquals(b, s.toByteArray(Charsets.ISO_8859_1)[0].toInt() and 0xFF)
        }
    }

    @Test fun delIs127() {
        assertEquals(127, CtrlKeys.DEL.code)
    }

    @Test fun withModifiersPlain() {
        assertEquals("b", CtrlKeys.withModifiers("b", false, false))
    }

    @Test fun withModifiersCtrlLetter() {
        val s = CtrlKeys.withModifiers("b", true, false)
        assertEquals(1, s.length)
        assertEquals(2, s.toByteArray(Charsets.ISO_8859_1)[0].toInt() and 0xFF)
    }

    @Test fun withModifiersAltLetter() {
        val s = CtrlKeys.withModifiers("b", false, true)
        assertEquals(2, s.length)
        assertEquals(27, s[0].code)
    }

    @Test fun withModifiersCtrlArrow() {
        assertEquals("\u001B[1;5A", CtrlKeys.withModifiers("\u001B[A", true, false))
        assertEquals("\u001B[1;5D", CtrlKeys.withModifiers("\u001B[D", true, false))
    }

    @Test fun withModifiersAltSpecial() {
        assertEquals("\u001B\u001B[A", CtrlKeys.withModifiers("\u001B[A", false, true))
    }

    @Test fun withModifiersCtrlPgUp() {
        assertEquals("\u001B[5;5~", CtrlKeys.withModifiers("\u001B[5~", true, false))
    }

    @Test fun withModifiersCtrlF1() {
        assertEquals("\u001B[1;5P", CtrlKeys.withModifiers("\u001BOP", true, false))
    }
}
