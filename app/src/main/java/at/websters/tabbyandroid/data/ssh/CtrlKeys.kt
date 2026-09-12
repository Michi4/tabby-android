package at.websters.tabbyandroid.data.ssh

/**
 * Ctrl/Alt key handling for the sticky modifier toggles and direct keyboard
 * input. Pure logic, fully unit-tested. No raw control bytes in source:
 * everything is computed from char codes.
 */
object CtrlKeys {
    /** Ctrl+letter -> 0x01..0x1A, plus common Ctrl+symbol mappings. Null if none. */
    fun ctrlByte(c: Char): Byte? {
        val u = c.uppercaseChar()
        if (u in 'A'..'Z') return (u.code - 'A'.code + 1).toByte()
        return when (c) {
            ' ' -> 0x00.toByte()
            '[' -> 0x1B.toByte()
            '\\' -> 0x1C.toByte()
            ']' -> 0x1D.toByte()
            '^' -> 0x1E.toByte()
            '_' -> 0x1F.toByte()
            '?' -> 0x7F.toByte()
            else -> null
        }
    }

    /** Alt+char = ESC prefix + char. */
    fun altSeq(c: Char): String = 27.toChar() + c.toString()

    /** Encodes a single byte 1:1 (ISO-8859-1 maps 0x00..0xFF directly). */
    fun byteString(b: Byte): String = String(byteArrayOf(b), Charsets.ISO_8859_1)

    val DEL: Char get() = 127.toChar()
}
