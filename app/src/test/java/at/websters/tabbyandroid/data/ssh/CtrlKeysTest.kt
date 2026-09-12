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
}
