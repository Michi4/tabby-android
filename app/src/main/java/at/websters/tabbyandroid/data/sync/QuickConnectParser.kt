package at.websters.tabbyandroid.data.sync

import at.websters.tabbyandroid.data.model.SshProfile
import java.util.UUID

/**
 * Termius-style quick connect: `user@host:port`, `[ipv6]:port`, `host:port`, `host`.
 * Mirrors Tabby desktop `SSHProfilesService.quickConnect`.
 */
object QuickConnectParser {
    fun parse(query: String, defaultUser: String = "root"): SshProfile {
        var q = query.trim()
        var user: String? = null
        var host = q
        var port = 22
        if (host.contains("@")) {
            val parts = host.split("@")
            host = parts.last()
            user = parts.dropLast(1).joinToString("@")
        }
        if (host.startsWith("[")) {
            val end = host.indexOf(']')
            if (end > 0) {
                val rest = host.substring(end + 1)
                host = host.substring(1, end)
                if (rest.startsWith(":")) rest.substring(1).toIntOrNull()?.let { port = it }
            }
        } else if (host.contains(":")) {
            val idx = host.lastIndexOf(':')
            host.substring(idx + 1).toIntOrNull()?.let {
                port = it
                host = host.substring(0, idx)
            }
        }
        return SshProfile(
            id = "quick:${UUID.randomUUID()}",
            name = q.ifEmpty { host },
            host = host,
            port = port.coerceIn(1, 65535),
            username = user?.takeIf { it.isNotBlank() } ?: defaultUser,
        )
    }
}
