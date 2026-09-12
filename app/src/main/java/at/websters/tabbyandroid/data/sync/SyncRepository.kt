package at.websters.tabbyandroid.data.sync

import at.websters.tabbyandroid.BuildConfig
import at.websters.tabbyandroid.data.local.ProfileRepository
import at.websters.tabbyandroid.data.local.SecureTokenStorage
import at.websters.tabbyandroid.data.model.RemoteConfigMeta
import at.websters.tabbyandroid.data.model.SshProfile
import at.websters.tabbyandroid.data.model.SyncAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sync flow (mirrors Tabby desktop ConfigSyncService):
 * - Pull: list configs -> pick one -> download YAML -> parse ssh profiles (read-only).
 * - Push: merge local profiles into remote YAML -> PATCH (explicit user action only).
 * All network on IO. Throws with clear messages for UI.
 */
class SyncRepository(
    private val profiles: ProfileRepository,
    private val secrets: SecureTokenStorage,
    private val apiFactory: (host: String, token: String) -> TabbySyncService = TabbySyncApiFactory::create,
) {
    suspend fun listRemoteConfigs(account: SyncAccount): List<RemoteConfigMeta> = withContext(Dispatchers.IO) {
        val token = secrets.getAccountToken(account.id)
        require(token.isNotBlank()) { "Missing sync token for '${account.name}'" }
        listRemoteConfigs(account.normalizedHost(), token)
    }

    /** Host+token variant used by the add-server dialog (persists nothing). */
    suspend fun listRemoteConfigs(host: String, token: String): List<RemoteConfigMeta> = withContext(Dispatchers.IO) {
        require(token.isNotBlank()) { "Enter your secret sync token" }
        val api = apiFactory(TabbySyncApiFactory.normalizeHost(host), token)
        api.listConfigs().map { RemoteConfigMeta(it.id, it.name, it.modifiedAt) }
    }

    suspend fun syncAccount(account: SyncAccount): SyncResult = withContext(Dispatchers.IO) {
        try {
            val token = secrets.getAccountToken(account.id)
            require(token.isNotBlank()) { "Missing sync token for '${account.name}'" }
            val configId = account.selectedConfigId
                ?: return@withContext SyncResult(false, 0, "No remote config selected for '${account.name}'")
            val api = apiFactory(TabbySyncApiFactory.normalizeHost(account.hostUrl), token)
            val full = api.getConfig(configId)
            val parsed = TabbyYamlParser.parseSshProfiles(
                full.content,
                originPrefix = "tabby:${account.id}:$configId",
            )
            val groups = TabbyYamlParser.parseGroupsFullYaml(full.content)
            SyncResult(true, parsed.size, null, parsed, groups)
        } catch (e: Exception) {
            SyncResult(false, 0, TabbySyncApiFactory.friendlyError(e))
        }
    }

    data class SyncResult(
        val ok: Boolean,
        val imported: Int,
        val error: String? = null,
        val profiles: List<SshProfile> = emptyList(),
        val groups: List<at.websters.tabbyandroid.data.model.TabbyGroup> = emptyList(),
    )

    /**
     * Uploads local profiles to the server. Only ever called from an explicit
     * Upload tap - never automatically - so a phone can never silently clobber
     * the desktop config. Unmanaged remote entries are preserved.
     */
    suspend fun upload(
        account: SyncAccount,
        localProfiles: List<SshProfile>,
        tombstoneIds: Set<String>,
    ): UploadResult = withContext(Dispatchers.IO) {
        try {
            val token = secrets.getAccountToken(account.id)
            require(token.isNotBlank()) { "Missing sync token for '${account.name}'" }
            val configId = account.selectedConfigId
                ?: return@withContext UploadResult(false, 0, 0, "No remote config selected for '${account.name}'")
            val api = apiFactory(TabbySyncApiFactory.normalizeHost(account.hostUrl), token)
            val remote = api.getConfig(configId)
            val merged = TabbyYamlSerializer.merge(remote.content, localProfiles, tombstoneIds)
            api.updateConfig(
                configId,
                UpdateConfigBody(
                    content = merged,
                    lastUsedWithVersion = "android-${BuildConfig.VERSION_NAME}",
                ),
            )
            UploadResult(true, localProfiles.size, tombstoneIds.size, null)
        } catch (e: Exception) {
            UploadResult(false, 0, 0, TabbySyncApiFactory.friendlyError(e))
        }
    }

    data class UploadResult(
        val ok: Boolean,
        val uploaded: Int,
        val removed: Int,
        val error: String? = null,
    )
}
