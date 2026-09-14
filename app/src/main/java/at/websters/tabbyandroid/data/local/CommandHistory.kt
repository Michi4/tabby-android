package at.websters.tabbyandroid.data.local

import kotlinx.serialization.Serializable

/**
 * Command history (frequency-ranked suggestions) + macros (one-tap commands).
 * Both live ENCRYPTED (commands routinely contain secrets), as single JSON
 * blobs. Ranking/recording are pure functions (unit-tested); only the thin
 * load/save touches storage.
 */
@Serializable
data class CmdEntry(
    val cmd: String,
    val count: Int = 1,
    val lastUsed: Long = 0L,
)

@Serializable
data class Macro(
    val name: String,
    val command: String,
)

/** Lines that must never be recorded (likely contain secrets). */
private val SENSITIVE_CMD = Regex(
    "password|passwd|passphrase|sshpass|secret|token|api[_-]?key|-----BEGIN|authorization",
    RegexOption.IGNORE_CASE,
)

fun isSensitiveCommand(cmd: String): Boolean = SENSITIVE_CMD.containsMatchIn(cmd)

/**
 * Records one submitted line. Returns the updated history (capped at [max],
 * least-used evicted). Blank + sensitive lines pass through unchanged.
 */
fun recordCommand(
    history: List<CmdEntry>,
    cmd: String,
    now: Long = System.currentTimeMillis(),
    max: Int = 200,
): List<CmdEntry> {
    val clean = cmd.trim()
    if (clean.isEmpty() || isSensitiveCommand(clean)) return history
    val kept = history.filterNot { it.cmd == clean }.toMutableList()
    val prev = history.find { it.cmd == clean }
    kept.add(0, CmdEntry(clean, (prev?.count ?: 0) + 1, now))
    return kept
        .sortedWith(compareByDescending<CmdEntry> { it.count }.thenByDescending { it.lastUsed })
        .take(max)
}

/**
 * Frequency-ranked completions for the current line (shell-history style).
 * Empty for short/blank prefixes so the bar stays out of the way.
 */
fun rankSuggestions(
    history: List<CmdEntry>,
    prefix: String,
    limit: Int = 5,
    minPrefix: Int = 2,
): List<String> {
    val p = prefix.trimStart()
    if (p.length < minPrefix) return emptyList()
    return history
        .filter { it.cmd.startsWith(p) && it.cmd != p }
        .sortedWith(compareByDescending<CmdEntry> { it.count }.thenByDescending { it.lastUsed })
        .take(limit)
        .map { it.cmd }
}
