package at.websters.tabbyandroid.data.model

import kotlinx.serialization.Serializable

/**
 * One Tabby sync endpoint: your own Tabby Web instance URL + secret sync token.
 * (Desktop Tabby: Settings → Config sync. Self-host via Eugeny/tabby-web.)
 * Multiple accounts are supported (Termius-like vaults / Tabby configs).
 */
@Serializable
data class SyncAccount(
    val id: String,
    val name: String,
    val hostUrl: String,
    /** Remote config id selected for this account (null = not chosen yet). */
    val selectedConfigId: Long? = null,
    val selectedConfigName: String? = null,
    val lastSyncAtEpochMs: Long? = null,
    val lastError: String? = null,
)

@Serializable
data class RemoteConfigMeta(
    val id: Long,
    val name: String,
    val modifiedAt: String? = null,
)

/** Unified SSH profile used by the app (manual + imported from Tabby). */
@Serializable
data class SshProfile(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String = "root",
    /** password | publicKey | keyboardInteractive | agent (agent = not supported on Android, falls back) */
    val authType: String = "password",
    val group: String? = null,
    /** Resolved display name for [group] (from the config's `groups` list). Never uploaded. */
    val groupName: String? = null,
    /** Local SSH key id to use for publicKey auth (device-only, never uploaded). */
    val keyId: String? = null,
    val color: String? = null,
    /** Origin: "manual" or "tabby:<accountId>:<configId>" */
    val origin: String = "manual",
    val keepaliveIntervalSec: Int = 5,
) {
    fun label(): String = if (username == "root") "$host:$port" else "$username@$host:$port"
}

/** Metadata for a locally stored SSH private key. Key material itself lives encrypted. */
@Serializable
data class SshKeyMeta(
    val id: String,
    val name: String,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
)

/** A profile folder. Folders nest via [parentId] (Termius imports use this). */
@Serializable
data class TabbyGroup(
    val id: String,
    val name: String,
    val parentId: String? = null,
)

/** A pinned host or folder. Order in the list defines display order. */
@Serializable
data class Pin(
    /** "host" (ref = profile id) or "group" (ref = group id). */
    val kind: String,
    val ref: String,
) {
    companion object {
        const val HOST = "host"
        const val GROUP = "group"
    }
}
