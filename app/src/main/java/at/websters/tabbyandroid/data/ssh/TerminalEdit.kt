package at.websters.tabbyandroid.data.ssh

/** A text-field edit expressed as deletions + inserted text (for terminal forwarding). */
data class EditOp(val deletions: Int, val added: String)

/**
 * Diffs old vs new field text via common prefix/suffix. Pure logic, unit-tested.
 * Covers typing, backspace, mid-line edits, paste and autocorrect replacements.
 */
fun diffEdit(old: String, new: String): EditOp {
    var prefix = 0
    while (prefix < old.length && prefix < new.length && old[prefix] == new[prefix]) {
        prefix++
    }
    var suffix = 0
    while (suffix < old.length - prefix && suffix < new.length - prefix &&
        old[old.length - 1 - suffix] == new[new.length - 1 - suffix]
    ) {
        suffix++
    }
    return EditOp(
        deletions = (old.length - prefix - suffix).coerceAtLeast(0),
        added = new.substring(prefix, new.length - suffix),
    )
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
