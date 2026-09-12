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
