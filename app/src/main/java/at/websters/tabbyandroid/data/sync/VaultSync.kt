package at.websters.tabbyandroid.data.sync

import at.websters.tabbyandroid.data.model.SshProfile
import at.websters.tabbyandroid.data.model.TabbyGroup

/**
 * Pure vault-aware sync logic (no Android, fully unit-tested).
 * [SyncRepository] only handles tokens, network and persistence around it.
 */
object VaultSync {

    sealed interface PullResolution {
        data class Ready(val profiles: List<SshProfile>, val groups: List<TabbyGroup>) : PullResolution
        data object Locked : PullResolution
        data class Failed(val message: String) : PullResolution
    }

    fun resolvePull(content: String, origin: String, passphrase: String?): PullResolution {
        val remoteMap = TabbyYamlParser.loadContentMap(content)
        val parsed = if (remoteMap == null) emptyList()
        else TabbyYamlParser.parseSshProfilesFromMap(remoteMap, origin)
        val groups = if (remoteMap == null) emptyList()
        else TabbyYamlParser.parseGroupsFull(remoteMap)
        if (parsed.isNotEmpty() || remoteMap == null) {
            return PullResolution.Ready(parsed, groups)
        }
        // no cleartext profiles: maybe a fully-encrypted vault
        val stored = VaultCrypto.parseStored(remoteMap)
            ?: return PullResolution.Ready(emptyList(), emptyList())
        if (passphrase.isNullOrBlank()) return PullResolution.Locked
        return try {
            val vault = VaultCrypto.decrypt(stored, passphrase)
            PullResolution.Ready(
                TabbyYamlParser.parseSshProfilesFromMap(vault.config, origin),
                TabbyYamlParser.parseGroupsFull(vault.config),
            )
        } catch (e: VaultBadPassphraseException) {
            PullResolution.Failed("Incorrect vault passphrase")
        } catch (e: VaultFormatException) {
            PullResolution.Failed(e.message ?: "Invalid vault")
        }
    }

    sealed interface PushResolution {
        data class Ready(val content: String) : PushResolution
        data object Locked : PushResolution
        data class Failed(val message: String) : PushResolution
    }

    fun buildUpload(
        remoteContent: String,
        localProfiles: List<SshProfile>,
        tombstoneIds: Set<String>,
        passphrase: String?,
    ): PushResolution {
        val remoteMap = TabbyYamlParser.loadContentMap(remoteContent)
        val stored = remoteMap?.let { VaultCrypto.parseStored(it) }
        if (stored == null || remoteMap == null) {
            return PushResolution.Ready(
                TabbyYamlSerializer.merge(remoteContent, localProfiles, tombstoneIds)
            )
        }
        if (passphrase.isNullOrBlank()) return PushResolution.Locked
        return try {
            val vault = VaultCrypto.decrypt(stored, passphrase)
            val inner = vault.config.toMutableMap()
            TabbyYamlSerializer.mergeMaps(inner, localProfiles, tombstoneIds)
            val reEncrypted = VaultCrypto.encrypt(inner, vault.secrets, passphrase)
            val outer = remoteMap.toMutableMap()
            outer["vault"] = mapOf(
                "version" to reEncrypted.version,
                "contents" to reEncrypted.contentsB64,
                "keySalt" to reEncrypted.saltHex,
                "iv" to reEncrypted.ivHex,
            )
            outer["encrypted"] = true
            PushResolution.Ready(TabbyYamlSerializer.dumpYaml(outer))
        } catch (e: VaultBadPassphraseException) {
            PushResolution.Failed("Incorrect vault passphrase")
        } catch (e: VaultFormatException) {
            PushResolution.Failed(e.message ?: "Invalid vault")
        }
    }
}
