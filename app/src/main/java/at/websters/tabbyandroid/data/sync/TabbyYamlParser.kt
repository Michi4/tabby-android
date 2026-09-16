package at.websters.tabbyandroid.data.sync

import at.websters.tabbyandroid.data.model.SshProfile
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/**
 * Parses Tabby `config.yaml` content (as stored in `api/1/configs/{id}.content`)
 * into [SshProfile]s.
 *
 * Real-world shape (Tabby >= 1.x, version: 7):
 * ```yaml
 * version: 7
 * profiles:
 *   - type: ssh
 *     name: relic02
 *     group: <uuid> (optional)
 *     color: '#ffd65b' (optional)
 *     options:
 *       host: 192.168.66.102
 *       port: 22
 *       user: acolyte
 *       auth: publicKey|password|null
 * ```
 * Tolerates: empty `{}`, missing `profiles`, non-ssh types, missing ports/users.
 */
object TabbyYamlParser {

    /**
     * Explicit SafeConstructor (never the default resolving constructor):
     * remote config YAML is untrusted input — global `!!java/object` tags
     * must never instantiate classes (CVE-2022-1471 class). SnakeYAML 2.x
     * happens to default to safe, but this pins the guarantee in code so a
     * future downgrade can't silently re-enable object construction.
     */
    private fun safeYaml(): Yaml = Yaml(SafeConstructor(LoaderOptions()))

    fun parseSshProfiles(yamlContent: String, originPrefix: String): List<SshProfile> {
        if (yamlContent.isBlank() || yamlContent.trim() == "{}") return emptyList()
        // NOTE: explicit <Any> - SnakeYAML's generic <T> T load(String) otherwise
        // infers Void from the try-expression and crashes with ClassCastException.
        val loaded: Any? = try {
            safeYaml().load<Any>(yamlContent)
        } catch (_: Exception) {
            return emptyList()
        }
        if (loaded !is Map<*, *>) return emptyList()
        return parseSshProfilesFromMap(loaded, originPrefix)
    }

    /** Same as [parseSshProfiles] but from an already-parsed map (e.g. decrypted vault JSON). */
    fun parseSshProfilesFromMap(root: Map<*, *>, originPrefix: String): List<SshProfile> {
        val profiles = root["profiles"] as? List<*> ?: return emptyList()
        val groups = parseGroups(root)
        return profiles.mapNotNull { parseOne(it, originPrefix, groups) }
    }

    /** Loads remote config content (YAML) into a plain map, or null when blank/invalid. */
    fun loadContentMap(yamlContent: String): MutableMap<String, Any?>? {
        if (yamlContent.isBlank() || yamlContent.trim() == "{}") return null
        val loaded = runCatching { safeYaml().load<Any>(yamlContent) }.getOrNull()
        @Suppress("UNCHECKED_CAST")
        return (loaded as? Map<*, *>)
            ?.entries
            ?.associate { (k, v) -> k.toString() to v }
            ?.toMutableMap() as? MutableMap<String, Any?>
    }

    /**
     * True when the server returned content that cannot be parsed as a Tabby
     * config mapping. Blank/`{}` are valid empty configs and deliberately do
     * not count as unreadable: treating a corrupt remote file as empty is how
     * sync/upload can silently erase desktop settings.
     */
    fun isUnreadableConfigContent(yamlContent: String): Boolean {
        if (yamlContent.isBlank() || yamlContent.trim() == "{}") return false
        return loadContentMap(yamlContent) == null
    }

    /** Resolves the config's `groups: [{id, name}]` (e.g. Termius-imported folders). */
    fun parseGroups(root: Map<*, *>): Map<String, String> =
        parseGroupsFull(root).associate { it.id to it.name }

    /** Full folder structures, including nesting via `parentGroupId`. */
    fun parseGroupsFull(root: Map<*, *>): List<at.websters.tabbyandroid.data.model.TabbyGroup> =
        (root["groups"] as? List<*>)
            ?.filterIsInstance<Map<*, *>>()
            ?.mapNotNull { g ->
                val id = g["id"] as? String
                val name = g["name"] as? String
                if (id.isNullOrBlank() || name.isNullOrBlank()) null
                else at.websters.tabbyandroid.data.model.TabbyGroup(
                    id = id,
                    name = name,
                    parentId = (g["parentGroupId"] as? String)?.takeIf { it.isNotBlank() },
                )
            }
            ?: emptyList()

    /** Folder structures straight from a config's YAML content. */
    fun parseGroupsFullYaml(yamlContent: String): List<at.websters.tabbyandroid.data.model.TabbyGroup> {
        if (yamlContent.isBlank() || yamlContent.trim() == "{}") return emptyList()
        val loaded = runCatching { safeYaml().load<Any>(yamlContent) }.getOrNull()
        return if (loaded is Map<*, *>) parseGroupsFull(loaded) else emptyList()
    }

    private fun parseOne(raw: Any?, originPrefix: String, groups: Map<String, String> = emptyMap()): SshProfile? {
        if (raw !is Map<*, *>) return null
        if ((raw["type"] as? String) != "ssh") return null
        val options = raw["options"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val host = (options["host"] as? String)?.trim().orEmpty()
        if (host.isEmpty()) return null
        val name = (raw["name"] as? String)?.trim().takeUnless { it.isNullOrEmpty() }
            ?: host
        val port = (options["port"] as? Number)?.toInt() ?: 22
        val user = (options["user"] as? String)?.trim().takeUnless { it.isNullOrEmpty() } ?: "root"
        val auth = (options["auth"] as? String)?.trim().takeUnless { it.isNullOrEmpty() } ?: "password"
        val group = (raw["group"] as? String)
            ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
        val color = raw["color"] as? String
        val id = (raw["id"] as? String)?.trim().takeUnless { it.isNullOrEmpty() }
            ?: "tabby:${name.hashCode()}:$host:$port:$user"
        val keepalive = (options["keepaliveInterval"] as? Number)?.toInt()?.div(1000)?.coerceIn(0, 3600) ?: 5
        return SshProfile(
            id = id,
            name = name,
            host = host,
            port = port.coerceIn(1, 65535),
            username = user,
            authType = auth,
            group = group,
            groupName = group?.let { groups[it] },
            color = color,
            origin = originPrefix,
            keepaliveIntervalSec = keepalive,
        )
    }
}
