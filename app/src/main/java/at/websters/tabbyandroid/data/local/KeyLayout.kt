package at.websters.tabbyandroid.data.local

import kotlinx.serialization.Serializable

/**
 * Fully customizable extended-keyboard layout (Settings → Terminal).
 *
 * A layout is 1–4 rows of key IDs plus the spacing between keys. Key IDs are
 * stable strings: `esc`, `tab`, `enter`, `ctrl`, `alt`, `altgr`, `left`,
 * `up`, `down`, `right`, `home`, `end`, `pgup`, `pgdn`, `ins`, `del`,
 * `f1`…`f12`, and single-character symbols (`|`, `~`, …). `ctrl`/`alt`/`altgr`
 * are one-shot modifier chips (tap = next key, double-tap = lock), everything
 * else sends its [keySeqFor] byte sequence. Unknown IDs are skipped when
 * rendering (never crash on old/stale layouts).
 */
@Serializable
data class KeyLayout(
    val rows: List<List<String>> = defaultKeyRows(),
    /** Gap between keys, dp (0–16). */
    val spacingDp: Int = 4,
)

/** Modifier-chip ids (toggles, not byte sequences). */
val MODIFIER_KEY_IDS: Set<String> = setOf("ctrl", "alt", "altgr")

/** Display label for an id (used by the editor + text fallback). */
fun keyLabelFor(id: String): String = when (id) {
    "esc" -> "Esc"
    "tab" -> "Tab"
    "enter" -> "Enter"
    "ctrl" -> "Ctrl"
    "alt" -> "Alt"
    "altgr" -> "AltGr"
    "left" -> "<-"
    "up" -> "Up"
    "down" -> "Dn"
    "right" -> "->"
    "home" -> "Home"
    "end" -> "End"
    "pgup" -> "PgUp"
    "pgdn" -> "PgDn"
    "ins" -> "Ins"
    "del" -> "Del"
    else -> id
}

/**
 * Byte sequence an id sends, or null for modifiers / unknown ids.
 * No raw control bytes in source: everything routes through ESC + literals.
 */
fun keySeqFor(id: String): String? {
    if (id in MODIFIER_KEY_IDS) return null
    val label = keyLabelFor(id)
    at.websters.tabbyandroid.ui.screens.TOP_ROW_KEYS.toMap()[label]?.let { return it }
    at.websters.tabbyandroid.ui.screens.NAV_ARROWS.toMap()[label]?.let { return it }
    at.websters.tabbyandroid.ui.screens.EDIT_SYMBOL_KEYS.toMap()[label]?.let { return it }
    if (id.length <= 3) {
        at.websters.tabbyandroid.ui.screens.FN_KEYS.toMap()[id.uppercase()]?.let { return it }
    }
    return null
}

/** Every id the editor may offer (modifiers + all sendable keys). */
fun allKeyIds(): List<String> = buildList {
    addAll(listOf("esc", "tab", "ctrl", "left", "up", "down", "right", "alt", "altgr"))
    add("enter")
    addAll(listOf("home", "end", "pgup", "pgdn", "ins", "del"))
    addAll(listOf("|", "~", "-", "_", "/", "\\", ":", ";", "\"", "'", "$", "&", "*", "=", "+", "!", "?", "#"))
    addAll((1..12).map { "f$it" })
}

fun defaultKeyRows(): List<List<String>> = listOf(
    listOf("esc", "tab", "ctrl", "left", "up", "down", "right", "alt", "altgr"),
    listOf(
        "enter", "home", "end", "pgup", "pgdn", "ins", "del",
        "|", "~", "-", "_", "/", "\\", ":", ";", "\"", "'", "$", "&",
        "*", "=", "+", "!", "?", "#",
    ),
    listOf("f1", "f2", "f3", "f4", "f5", "f6", "f7", "f8", "f9", "f10", "f11", "f12"),
)

/** Storage sanitizer (pure, unit-tested): unknown ids dropped, bounds clamped. */
fun sanitizeKeyLayout(layout: KeyLayout): KeyLayout {
    val known = allKeyIds().toSet()
    val rows = layout.rows
        .map { row -> row.filter { it in known }.distinct() }
        .filter { it.isNotEmpty() }
        .take(4)
    val safeRows = rows.ifEmpty { defaultKeyRows() }
    return KeyLayout(safeRows, layout.spacingDp.coerceIn(0, 16))
}
