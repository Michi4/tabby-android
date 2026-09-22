package at.websters.tabbyandroid.data.sync

import at.websters.tabbyandroid.data.model.SshProfile
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

/**
 * Serializes [SshProfile]s back into Tabby `config.yaml` shape and merges them
 * into a remote config without touching anything else (desktop settings,
 * local profiles, unknown keys are preserved as parsed maps).
 *
 * Merge rule: drop remote `profiles` entries that are tombstoned (deleted on
 * this device). Entries we manage are deep-merged: our connection fields win,
 * every other desktop-only field (privateKeys, passwords, scripts, port
 * forwards, algorithms, icons, …) is preserved. New local profiles are
 * appended. Everything else in the YAML is left alone, so Upload is safe.
 */
object TabbyYamlSerializer {

    fun profileToMap(p: SshProfile): Map<String, Any?> {
        val options = LinkedHashMap<String, Any?>()
        options["host"] = p.host
        options["port"] = p.port
        options["user"] = p.username
        options["auth"] = p.authType
        if (p.keepaliveIntervalSec != 5) {
            options["keepaliveInterval"] = p.keepaliveIntervalSec * 1000
        }
        val map = LinkedHashMap<String, Any?>()
        map["type"] = "ssh"
        map["name"] = p.name
        map["id"] = p.id
        if (!p.group.isNullOrBlank()) map["group"] = p.group
        if (!p.color.isNullOrBlank()) map["color"] = p.color
        map["options"] = options
        return map
    }

    /**
     * @param remoteYaml current server content (may be "{}", blank, or full config)
     * @param replacements our current profiles for this config
     * @param tombstoneIds ids deleted on this device (excluded even if still remote)
     */
    fun merge(
        remoteYaml: String,
        replacements: List<SshProfile>,
        tombstoneIds: Set<String> = emptySet(),
    ): String {
        val base = TabbyYamlParser.loadContentMap(remoteYaml) ?: LinkedHashMap()
        mergeMaps(base, replacements, tombstoneIds)
        return dumpYaml(base)
    }

    /** Merges profiles into an already-parsed config map (remote YAML or decrypted vault). */
    fun mergeMaps(
        base: MutableMap<String, Any?>,
        replacements: List<SshProfile>,
        tombstoneIds: Set<String> = emptySet(),
    ): MutableMap<String, Any?> {
        if (!base.containsKey("version")) base["version"] = 7
        @Suppress("UNCHECKED_CAST")
        val existing = (base["profiles"] as? List<*>)
            ?.filterIsInstance<Map<*, *>>()
            ?: emptyList()
        val remoteById = existing.associateBy { it["id"] as? String }
        val managedIds = replacements.map { it.id }.toSet() + tombstoneIds
        val kept = existing.filter { (it["id"] as? String) !in managedIds }
        val out = kept.toMutableList<Map<*, *>>()
        for (p in replacements) {
            val remote = remoteById[p.id]
            out.add(if (remote != null) mergeProfile(remote, p) else profileToMap(p))
        }
        base["profiles"] = out
        return base
    }

    fun dumpYaml(map: Map<String, Any?>): String {
        val opts = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
            // Never wrap long scalars: a wrapped base64 vault would fold with
            // spaces on re-parse and fail to decode. Desktop emits one long line.
            width = Int.MAX_VALUE
        }
        return quoteVaultScalars(Yaml(opts).dump(map))
    }

    // Matches the scalar on a `keySalt:` / `iv:` / `contents:` line when it is
    // NOT already quoted and NOT a block-scalar header (>, >-, |, |-).
    private val vaultScalarLine =
        Regex("""^(\s*(?:keySalt|iv|contents):\s*)([^'"\s>|][^#\n]*?)\s*$""", RegexOption.MULTILINE)

    /**
     * Forces single quotes around vault scalars. SnakeYAML omits quotes for
     * hex like "12e34…", but Tabby desktop (js-yaml) then reads it as a
     * float/Infinity and its next save persists "keySalt: .inf", killing the
     * vault. Quoted scalars round-trip as strings in every YAML implementation.
     */
    fun quoteVaultScalars(yaml: String): String =
        vaultScalarLine.replace(yaml) { m -> "${m.groupValues[1]}'${m.groupValues[2]}'" }

    /**
     * Deep-merges one profile: starts from the remote desktop entry (keeps
     * privateKeys, password refs, scripts, forwards, algorithms, icon, …)
     * and overlays only the connection fields this app manages.
     * Display-only [SshProfile.groupName] is never written back.
     */
    fun mergeProfile(remote: Map<*, *>, p: SshProfile): Map<String, Any?> {
        val merged = LinkedHashMap<String, Any?>()
        remote.forEach { (k, v) -> merged[k.toString()] = v }
        merged["type"] = "ssh"
        merged["name"] = p.name
        merged["id"] = p.id
        if (!p.group.isNullOrBlank()) merged["group"] = p.group
        if (!p.color.isNullOrBlank()) merged["color"] = p.color
        val options = LinkedHashMap<String, Any?>()
        (remote["options"] as? Map<*, *>)?.forEach { (k, v) -> options[k.toString()] = v }
        options["host"] = p.host
        options["port"] = p.port
        options["user"] = p.username
        options["auth"] = p.authType
        if (p.keepaliveIntervalSec != 5) {
            options["keepaliveInterval"] = p.keepaliveIntervalSec * 1000
        }
        merged["options"] = options
        return merged
    }

    /** Origin format used for profiles pulled from a sync account. */
    fun originFor(accountId: String, configId: Long): String = "tabby:$accountId:$configId"

    /** Extracts the sync-account id from a profile origin, or null for manual profiles. */
    fun accountIdFromOrigin(origin: String): String? {
        val parts = origin.split(":")
        return if (parts.size >= 3 && parts[0] == "tabby") parts[1] else null
    }
}
