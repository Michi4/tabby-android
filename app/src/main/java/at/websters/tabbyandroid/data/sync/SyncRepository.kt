package at.websters.tabbyandroid.data.sync

import at.websters.tabbyandroid.BuildConfig
import at.websters.tabbyandroid.data.local.ProfileRepository
import at.websters.tabbyandroid.data.local.SecureTokenStorage
import at.websters.tabbyandroid.data.model.RemoteConfigMeta
import at.websters.tabbyandroid.data.model.SshProfile
import at.websters.tabbyandroid.data.model.SyncAccount
import at.websters.tabbyandroid.data.model.TabbyGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sync flow (mirrors Tabby desktop ConfigSyncService):
 * - Pull: list configs -> pick one -> download YAML -> parse ssh profiles (read-only).
 * - Vault configs: same, but the content is an encrypted envelope that needs
 *   the vault passphrase (asked on-device, never logged, optionally remembered).
 * - Push: merge local profiles into remote YAML (or re-encrypted vault) -> PATCH,
 *   explicit user action only.
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

    suspend fun syncAccount(account: SyncAccount, vaultPassphrase: String? = null): SyncResult =
        withContext(Dispatchers.IO) {
            try {
                val token = secrets.getAccountToken(account.id)
                require(token.isNotBlank()) { "Missing sync token for '${account.name}'" }
                val configId = account.selectedConfigId
                    ?: return@withContext SyncResult(false, 0, "No remote config selected for '${account.name}'")
                val api = apiFactory(TabbySyncApiFactory.normalizeHost(account.hostUrl), token)
                val full = api.getConfig(configId)
                val origin = "tabby:${account.id}:$configId"
                when (val r = VaultSync.resolvePull(full.content, origin, vaultPassphrase)) {
                    is VaultSync.PullResolution.Ready ->
                        SyncResult(true, r.profiles.size, null, r.profiles, r.groups)
                    is VaultSync.PullResolution.Locked ->
                        SyncResult(false, 0, "Vault locked — enter the vault passphrase", vaultLocked = true)
                    is VaultSync.PullResolution.Failed ->
                        SyncResult(false, 0, r.message)
                }
            } catch (e: Exception) {
                SyncResult(false, 0, TabbySyncApiFactory.friendlyError(e))
            }
        }

    data class SyncResult(
        val ok: Boolean,
        val imported: Int,
        val error: String? = null,
        val profiles: List<SshProfile> = emptyList(),
        val groups: List<TabbyGroup> = emptyList(),
        /** Remote is a fully-encrypted vault and no passphrase was given. */
        val vaultLocked: Boolean = false,
    )

    /**
     * Uploads local profiles to the server. Only ever called from an explicit
     * Upload tap - never automatically - so a phone can never silently clobber
     * the desktop config. Unmanaged remote entries are preserved (inside or
     * outside the vault alike).
     */
    suspend fun upload(
        account: SyncAccount,
        localProfiles: List<SshProfile>,
        tombstoneIds: Set<String>,
        vaultPassphrase: String? = null,
    ): UploadResult = withContext(Dispatchers.IO) {
        try {
            val token = secrets.getAccountToken(account.id)
            require(token.isNotBlank()) { "Missing sync token for '${account.name}'" }
            val configId = account.selectedConfigId
                ?: return@withContext UploadResult(false, 0, 0, "No remote config selected for '${account.name}'")
            val api = apiFactory(TabbySyncApiFactory.normalizeHost(account.hostUrl), token)
            val remote = api.getConfig(configId)
            val merged = when (val r = VaultSync.buildUpload(remote.content, localProfiles, tombstoneIds, vaultPassphrase)) {
                is VaultSync.PushResolution.Ready -> r.content
                is VaultSync.PushResolution.Locked ->
                    return@withContext UploadResult(false, 0, 0, "Vault locked — enter the vault passphrase", vaultLocked = true)
                is VaultSync.PushResolution.Failed ->
                    return@withContext UploadResult(false, 0, 0, r.message)
            }
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
        val vaultLocked: Boolean = false,
    )
}

/** Session + optionally remembered vault passphrases, per sync account. Never logged. */
object VaultPassphrases {
    private val mem = mutableMapOf<String, String>()
    @Synchronized fun put(accountId: String, passphrase: String) { mem[accountId] = passphrase }
    @Synchronized fun peek(accountId: String): String? = mem[accountId]
    @Synchronized fun clear(accountId: String) { mem.remove(accountId) }
}

/** Accounts whose remote config is vault-locked (UI unlock prompt state). */
object VaultLocks {
    val locked = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    fun set(accountId: String) { locked.value = locked.value + accountId }
    fun clear(accountId: String) { locked.value = locked.value - accountId }
}
