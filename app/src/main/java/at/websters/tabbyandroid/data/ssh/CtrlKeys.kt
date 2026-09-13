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

    /** AltGr behaves like Alt over SSH (ESC prefix). */
    fun altGrSeq(c: Char): String = altSeq(c)

    /**
     * Applies one-shot modifiers to any key sequence (letters AND special
     * keys). Single chars use ctrlByte/ESC-prefix; CSI/SS3 specials use
     * xterm modifyOtherKeys style (`;5` = Ctrl, extra ESC = Alt), so
     * Ctrl+arrows, Alt+F-keys etc. all arrive correctly in tmux/vim/btop.
     */
    fun withModifiers(seq: String, ctrl: Boolean, alt: Boolean): String {
        if (!ctrl && !alt) return seq
        if (seq.length == 1) {
            val c = seq[0]
            var base = seq
            if (ctrl) {
                val b = if (c.isLetter() || c == ' ' || c in "[]\\^_?") ctrlByte(c) else null
                base = if (b != null) byteString(b) else seq
            }
            return if (alt) 27.toChar() + base else base
        }
        if (seq == "\r") {
            val base = if (ctrl) "\n" else seq
            return if (alt) 27.toChar() + base else base
        }
        if (seq == "\u0009") {
            return if (alt) 27.toChar() + seq else seq
        }
        if (seq.isNotEmpty() && seq[0] == 27.toChar()) {
            var core = seq
            if (ctrl) core = ctrlSpecial(core)
            return if (alt) 27.toChar().toString() + core else core
        }
        return seq
    }

    /** Maps a plain xterm special to its Ctrl variant (`;5`), best-effort. */
    private fun ctrlSpecial(seq: String): String {
        val esc = 27.toChar()
        return when (seq) {
            "$esc[A" -> "$esc[1;5A"
            "$esc[B" -> "$esc[1;5B"
            "$esc[C" -> "$esc[1;5C"
            "$esc[D" -> "$esc[1;5D"
            "$esc[H" -> "$esc[1;5H"
            "$esc[F" -> "$esc[1;5F"
            "$esc[2~", "$esc[3~", "$esc[5~", "$esc[6~" ->
                seq.replace("~", ";5~")
            "$esc[15~", "$esc[17~", "$esc[18~", "$esc[19~",
            "$esc[20~", "$esc[21~", "$esc[23~", "$esc[24~" ->
                seq.replace("~", ";5~")
            "${esc}OP" -> "$esc[1;5P"
            "${esc}OQ" -> "$esc[1;5Q"
            "${esc}OR" -> "$esc[1;5R"
            "${esc}OS" -> "$esc[1;5S"
            else -> seq
        }
    }

    /** Encodes a single byte 1:1 (ISO-8859-1 maps 0x00..0xFF directly). */
    fun byteString(b: Byte): String = String(byteArrayOf(b), Charsets.ISO_8859_1)

    val DEL: Char get() = 127.toChar()
}
