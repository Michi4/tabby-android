package at.websters.tabbyandroid.data.ssh

/** A text-field edit expressed as deletions + inserted text (for terminal forwarding). */
data class EditOp(val deletions: Int, val added: String)

/**
 * Diffs old vs new text at Unicode code-point boundaries and counts deletions
 * in code points, not UTF-16 units. This keeps surrogate pairs intact and
 * sends one DEL for one erased server-side character.
 */
fun diffEdit(old: String, new: String): EditOp {
    var prefix = 0
    while (prefix < old.length && prefix < new.length) {
        val co = old.codePointAt(prefix)
        val cn = new.codePointAt(prefix)
        if (co != cn) break
        prefix += Character.charCount(co)
    }
    var suffix = 0
    while (suffix < old.length - prefix && suffix < new.length - prefix) {
        val co = old.codePointBefore(old.length - suffix)
        val cn = new.codePointBefore(new.length - suffix)
        if (co != cn) break
        suffix += Character.charCount(co)
    }
    val removed = old.substring(prefix, old.length - suffix)
    return EditOp(
        deletions = codePointCount(removed),
        added = new.substring(prefix, new.length - suffix),
    )
}

private fun codePointCount(s: String): Int {
    var i = 0
    var n = 0
    while (i < s.length) {
        i += Character.charCount(s.codePointAt(i))
        n++
    }
    return n
}

/**
 * Zero-width sentinel kept in the otherwise always-empty terminal sender
 * field. It is invisible, never sent to the server, and makes soft-keyboard
 * backspace observable (deleting it yields exactly one deletion).
 */
const val SENDER_SENTINEL = "\uFEFF"

/** A sender-field change mapped to SSH bytes: literal text + DEL count. */
data class SenderEdit(val sendText: String, val deletions: Int)

/**
 * Arrow-key sequence mirroring a pure cursor move (same text, collapsed
 * selection only moved) to the remote line — this is what makes Gboard's
 * spacebar-swipe move the server cursor. Null unless the move is safe to
 * forward: identical text, both ends collapsed, non-negative, changed.
 * One repeat per character step; the caller sends the sequence as-is (plain
 * arrows, no modifiers — a cursor glide is never a modified key).
 */
fun cursorMoveArrows(oldText: String, oldPos: Int, newText: String, newPos: Int): String? {
    if (oldText != newText) return null
    if (oldPos < 0 || newPos < 0 || oldPos == newPos) return null
    val step = if (newPos > oldPos) CURSOR_RIGHT else CURSOR_LEFT
    return step.repeat(kotlin.math.abs(newPos - oldPos))
}

const val CURSOR_LEFT = "\u001B[D"
const val CURSOR_RIGHT = "\u001B[C"

/**
 * Maps one sender-field change to what must go over SSH. The field is reset
 * to [SENDER_SENTINEL] after every handled change, so `old` always contains
 * the sentinel and `new` is whatever the keyboard committed. Pure, tested.
 */
fun senderEdit(old: String, new: String): SenderEdit {
    val op = diffEdit(old, new)
    return SenderEdit(
        sendText = op.added.replace(SENDER_SENTINEL, ""),
        deletions = op.deletions,
    )
}
