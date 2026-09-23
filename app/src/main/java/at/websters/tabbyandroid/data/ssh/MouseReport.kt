package at.websters.tabbyandroid.data.ssh

/**
 * Mouse-event bytes for touch forwarding (tap-to-click, scroll wheel) into
 * apps that enabled mouse tracking (vim, opencode, tmux, less …). Pure
 * logic, fully unit-tested.
 *
 * Two encodings, like xterm:
 * - SGR (1006, modern): `ESC [ < Cb ; Cx ; Cy M` (press) / `... m` (release).
 *   Coordinates are 1-based and unbounded.
 * - X10 (legacy, no 1006): `ESC [ M Cb Cx Cy` with +32 offsets, press-only
 *   (a release is reported as button-3 press); coordinates clamp to 223.
 *
 * Button codes: 0 = left press, 3 = release, 64/65 = wheel up/down
 * (press-only, no release event — like a real scroll wheel).
 */
object MouseReport {
    const val LEFT = 0
    const val RELEASE = 3
    const val WHEEL_UP = 64
    const val WHEEL_DOWN = 65

    /** Press event (`M` terminator in SGR). */
    fun press(button: Int, col1: Int, row1: Int, sgr: Boolean): String =
        if (sgr) sgrSeq(button, col1, row1, press = true)
        else x10Seq(button, col1, row1)

    /** Release event (SGR `m`; X10 reports it as button-3 press). */
    fun release(col1: Int, row1: Int, sgr: Boolean): String =
        if (sgr) sgrSeq(RELEASE, col1, row1, press = false)
        else x10Seq(RELEASE, col1, row1)

    /** One wheel notch (press-only in both encodings). */
    fun wheel(up: Boolean, col1: Int, row1: Int, sgr: Boolean): String =
        press(if (up) WHEEL_UP else WHEEL_DOWN, col1, row1, sgr)

    /** Full tap = press immediately followed by release. */
    fun tap(col1: Int, row1: Int, sgr: Boolean): String =
        press(LEFT, col1, row1, sgr) + release(col1, row1, sgr)

    private fun sgrSeq(button: Int, col1: Int, row1: Int, press: Boolean): String =
        "\u001B[<$button;${col1.coerceAtLeast(1)};${row1.coerceAtLeast(1)}${if (press) "M" else "m"}"

    private fun x10Seq(button: Int, col1: Int, row1: Int): String {
        // Bytes are value+32; coordinates cap at 223 (byte cap 255), but
        // button codes (0/3/64/65) pass through untouched.
        fun coord(v: Int): Char = (32 + v.coerceIn(1, 223)).toChar()
        return "\u001B[M${(32 + button).toChar()}${coord(col1)}${coord(row1)}"
    }
}
