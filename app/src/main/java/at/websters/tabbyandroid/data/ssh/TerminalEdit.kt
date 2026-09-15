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
