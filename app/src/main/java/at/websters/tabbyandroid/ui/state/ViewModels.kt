package at.websters.tabbyandroid.ui.state

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import at.websters.tabbyandroid.data.local.ProfileRepository
import at.websters.tabbyandroid.data.local.SecureTokenStorage
import at.websters.tabbyandroid.data.model.RemoteConfigMeta
import at.websters.tabbyandroid.data.model.Pin
import at.websters.tabbyandroid.data.model.SshProfile
import at.websters.tabbyandroid.data.model.SyncAccount
import at.websters.tabbyandroid.data.ssh.SshConnection
import at.websters.tabbyandroid.data.sync.QuickConnectParser
import at.websters.tabbyandroid.data.sync.SyncRepository
import at.websters.tabbyandroid.data.sync.TabbyYamlSerializer
import at.websters.tabbyandroid.data.sync.VaultLocks
import at.websters.tabbyandroid.data.sync.VaultPassphrases
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ConnectionsUiState(
    val query: String = "",
    val syncing: Boolean = false,
    val syncMessage: String? = null,
    val profiles: List<SshProfile> = emptyList(),
)

class ConnectionsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ProfileRepository(app)
    private val secrets = SecureTokenStorage(app)
    private val sync = SyncRepository(repo, secrets)

    private val _query = MutableStateFlow("")
    private val _syncing = MutableStateFlow(false)
    private val _msg = MutableStateFlow<String?>(null)

    val state: StateFlow<ConnectionsUiState> = combine(
        _query, _syncing, _msg, repo.cachedProfiles, repo.manualProfiles,
    ) { q, syncing, msg, cached, manual ->
        val all = (manual + cached)
            .distinctBy { it.id }
            .filter {
                q.isBlank() ||
                    it.name.contains(q, true) || it.host.contains(q, true) ||
                    it.username.contains(q, true)
            }
            .sortedWith(compareBy({ it.groupName ?: it.group ?: "" }, { it.name.lowercase() }))
        ConnectionsUiState(q, syncing, msg, all)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConnectionsUiState())

    fun setQuery(q: String) { _query.value = q }

    fun quickConnect(query: String): SshProfile = QuickConnectParser.parse(query)

    suspend fun saveManualProfile(profile: SshProfile, password: String) {
        val manual = repo.manualProfiles.first().toMutableList()
        val idx = manual.indexOfFirst { it.id == profile.id }
        if (idx >= 0) manual[idx] = profile else manual.add(profile)
        repo.saveManual(manual)
        if (password.isNotBlank()) secrets.putSshPassword(profile.id, password)
    }

    suspend fun deleteProfile(profile: SshProfile) {
        if (profile.origin == "manual") {
            repo.saveManual(repo.manualProfiles.first().filterNot { it.id == profile.id })
        } else {
            repo.saveCached(repo.cachedProfiles.first().filterNot { it.id == profile.id })
            // Remember so the next Upload also removes it server-side.
            TabbyYamlSerializer.accountIdFromOrigin(profile.origin)
                ?.let { repo.addTombstone(it, profile.id) }
        }
    }

    fun getPassword(profileId: String): String = secrets.getSshPassword(profileId)

    /** Stores a password without touching (or duplicating) the profile itself. */
    suspend fun savePassword(profileId: String, password: String) {
        if (password.isNotBlank()) secrets.putSshPassword(profileId, password)
    }

    /** Updates a synced profile in the local cache (e.g. key assignment). Never uploads by itself. */
    suspend fun updateSyncedProfile(profile: SshProfile) {
        repo.saveCached(repo.cachedProfiles.first().map { if (it.id == profile.id) profile else it })
    }

    fun syncAll(onTabsRefresh: () -> Unit = {}) {
        viewModelScope.launch {
            _syncing.value = true
            _msg.value = null
            try {
                val accounts = repo.accounts.first()
                if (accounts.isEmpty()) {
                    _msg.value = "Add your Tabby Web instance first (Settings → Sync)"
                    return@launch
                }
                val merged = mutableListOf<SshProfile>()
                val mergedGroups = LinkedHashMap<String, at.websters.tabbyandroid.data.model.TabbyGroup>()
                // keep manual untouched; rebuild cached from all accounts
                val errors = mutableListOf<String>()
                val updatedAccounts = accounts.map { acc ->
                    // guarded vaults never auto-read: every unlock goes through biometrics
                    val mode = repo.currentVaultLockModes()[acc.id]
                        ?: ProfileRepository.LOCK_SESSION
                    val vaultPw = VaultPassphrases.peek(acc.id)
                        ?: if (mode == ProfileRepository.LOCK_FOREVER) {
                            secrets.getVaultPassphrase(acc.id).takeIf { it.isNotBlank() }
                        } else {
                            null
                        }
                    val r = sync.syncAccount(acc, vaultPw)
                    if (r.ok) {
                        merged += r.profiles
                        r.groups.forEach { mergedGroups.putIfAbsent(it.id, it) }
                        // Drop tombstones the server no longer has (deleted on desktop too).
                        repo.retainTombstones(acc.id, r.profiles.map { it.id }.toSet())
                        VaultLocks.clear(acc.id)
                        acc.copy(lastSyncAtEpochMs = System.currentTimeMillis(), lastError = null)
                    } else {
                        if (r.vaultLocked) VaultLocks.set(acc.id)
                        errors += "${acc.name}: ${r.error}"
                        acc.copy(lastError = r.error)
                    }
                }
                repo.saveAccounts(updatedAccounts)
                repo.saveCached(merged)
                repo.saveGroups(mergedGroups.values.toList())
                _msg.value = if (errors.isEmpty()) {
                    if (merged.isEmpty()) "Synced — remote config is empty ({}). Add SSH profiles on desktop first."
                    else "Synced ${merged.size} profiles"
                } else errors.joinToString("\n")
            } finally {
                _syncing.value = false
            }
        }
    }

    fun dismissMessage() { _msg.value = null }

    /**
     * Unlocks a vault-encrypted config. [mode] is one of [ProfileRepository] lock modes:
     * - session (default): memory only, asked again after restart;
     * - forever: also kept encrypted (Keystore), no further prompts;
     * - guarded: handled via biometrics - see [finishGuardedEnroll]/biometric unlock.
     */
    fun unlockVault(account: SyncAccount, passphrase: String, mode: String) {
        viewModelScope.launch {
            if (passphrase.isBlank()) return@launch
            repo.setVaultLockMode(account.id, mode)
            when (mode) {
                ProfileRepository.LOCK_FOREVER -> {
                    VaultPassphrases.put(account.id, passphrase)
                    secrets.putVaultPassphrase(account.id, passphrase)
                }
                ProfileRepository.LOCK_GUARDED -> {
                    _msg.value = "Use biometrics to seal the passphrase first"
                    return@launch
                }
                else -> {
                    VaultPassphrases.put(account.id, passphrase)
                    secrets.putVaultPassphrase(account.id, "")
                    repo.saveVaultSealed(account.id, null)
                }
            }
            VaultLocks.clear(account.id)
            syncAll()
        }
    }

    /** After a successful biometric open: session-only, then pull. */
    fun unlockVaultWithPlain(account: SyncAccount, plain: String) {
        viewModelScope.launch {
            VaultPassphrases.put(account.id, plain)
            VaultLocks.clear(account.id)
            syncAll()
        }
    }

    /** After sealing a passphrase behind biometrics: persist blob + mode, then pull. */
    fun finishGuardedEnroll(account: SyncAccount, sealedBlob: String, plain: String) {
        viewModelScope.launch {
            repo.saveVaultSealed(account.id, sealedBlob)
            repo.setVaultLockMode(account.id, ProfileRepository.LOCK_GUARDED)
            secrets.putVaultPassphrase(account.id, "")
            VaultPassphrases.put(account.id, plain)
            VaultLocks.clear(account.id)
            syncAll()
        }
    }

    fun forgetVaultPassphrase(account: SyncAccount) {
        VaultPassphrases.clear(account.id)
        viewModelScope.launch {
            secrets.putVaultPassphrase(account.id, "")
            repo.saveVaultSealed(account.id, null)
            _msg.value = "Vault passphrase forgotten for '${account.name}'"
        }
    }

    val vaultModes: StateFlow<Map<String, String>> = repo.vaultLockModes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val vaultSealed: StateFlow<Map<String, String>> = repo.vaultSealed
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val pins: StateFlow<List<Pin>> = repo.pins
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val collapsed: StateFlow<Set<String>> = repo.collapsed
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
    val groups: StateFlow<List<at.websters.tabbyandroid.data.model.TabbyGroup>> = repo.groups
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun toggleCollapse(key: String) {
        viewModelScope.launch {
            val cur = repo.collapsed.first().toMutableSet()
            if (!cur.add(key)) cur.remove(key)
            repo.saveCollapsed(cur)
        }
    }

    fun togglePinHost(p: SshProfile) {
        viewModelScope.launch {
            val cur = repo.pins.first().toMutableList()
            val i = cur.indexOfFirst { it.kind == Pin.HOST && it.ref == p.id }
            if (i >= 0) cur.removeAt(i) else cur.add(Pin(Pin.HOST, p.id))
            repo.savePins(cur)
        }
    }

    fun togglePinGroup(groupId: String) {
        viewModelScope.launch {
            val cur = repo.pins.first().toMutableList()
            val i = cur.indexOfFirst { it.kind == Pin.GROUP && it.ref == groupId }
            if (i >= 0) cur.removeAt(i) else cur.add(Pin(Pin.GROUP, groupId))
            repo.savePins(cur)
        }
    }

    fun movePin(ref: String, kind: String, delta: Int) {
        viewModelScope.launch {
            val cur = repo.pins.first().toMutableList()
            val i = cur.indexOfFirst { it.kind == kind && it.ref == ref }
            val j = (i + delta).coerceIn(0, cur.size - 1)
            if (i >= 0 && j != i) {
                val item = cur.removeAt(i)
                cur.add(j, item)
                repo.savePins(cur)
            }
        }
    }
}

/** Holds live SSH tabs (browser-style, Termius-like). Survives rotation via ViewModel. */
class TerminalTabsViewModel(app: Application) : AndroidViewModel(app) {
    data class Tab(val id: String = UUID.randomUUID().toString(), val profile: SshProfile, val conn: SshConnection)

    private val repo = ProfileRepository(app)

    private val _tabs = MutableStateFlow<List<Tab>>(emptyList())
    val tabs: StateFlow<List<Tab>> = _tabs
    private val _active = MutableStateFlow<String?>(null)
    val active: StateFlow<String?> = _active

    /**
     * Terminal defaults (Settings → Terminal); new tabs pick these up.
     * Eagerly hot so the values are loaded before any tab can open —
     * a lazily-started share could bake fallback defaults into a tab
     * that composes before the first DataStore emission lands.
     */
    val uiPrefs: StateFlow<at.websters.tabbyandroid.data.local.UiPrefs> = combine(
        repo.uiFontSize, repo.uiFollow, repo.uiKeyRows,
    ) { fontSize, follow, keyRows ->
        at.websters.tabbyandroid.data.local.UiPrefs(fontSize, follow, keyRows)
    }.stateIn(
        viewModelScope, SharingStarted.Eagerly,
        at.websters.tabbyandroid.data.local.UiPrefs(),
    )

    fun setUiFontSize(sizeSp: Int) {
        viewModelScope.launch { repo.setUiFontSize(sizeSp) }
    }

    fun setUiFollow(follow: Boolean) {
        viewModelScope.launch { repo.setUiFollow(follow) }
    }

    fun setUiKeyRows(rows: Int) {
        viewModelScope.launch { repo.setUiKeyRows(rows) }
    }

    fun open(profile: SshProfile): String {
        val knownHosts = java.io.File(getApplication<Application>().filesDir, "known_hosts")
        val tab = Tab(profile = profile, conn = SshConnection(profile, knownHostsFile = knownHosts))
        _tabs.value = _tabs.value + tab
        _active.value = tab.id
        return tab.id
    }

    fun openQuick(query: String): String = open(QuickConnectParser.parse(query))

    fun select(id: String) { _active.value = id }

    fun close(id: String) {
        _tabs.value.find { it.id == id }?.let {
            it.conn.close()
            at.websters.tabbyandroid.ui.screens.SessionPasswords.take(it.profile.id)
            at.websters.tabbyandroid.ui.screens.SessionKeys.take(it.profile.id)
        }
        _tabs.value = _tabs.value.filterNot { it.id == id }
        if (_active.value == id) _active.value = _tabs.value.lastOrNull()?.id
    }

    override fun onCleared() {
        _tabs.value.forEach {
            runCatching { it.conn.close() }
            at.websters.tabbyandroid.ui.screens.SessionPasswords.take(it.profile.id)
            at.websters.tabbyandroid.ui.screens.SessionKeys.take(it.profile.id)
        }
    }
}

class SyncAccountsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ProfileRepository(app)
    private val secrets = SecureTokenStorage(app)
    private val sync = SyncRepository(repo, secrets)

    val accounts: StateFlow<List<SyncAccount>> = repo.accounts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _msg = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _msg

    fun saveAccount(account: SyncAccount, token: String) {
        viewModelScope.launch {
            val current = repo.accounts.first().toMutableList()
            val idx = current.indexOfFirst { it.id == account.id }
            if (idx >= 0) current[idx] = account else current.add(account)
            repo.saveAccounts(current)
            if (token.isNotBlank()) secrets.putAccountToken(account.id, token)
            _msg.value = "Saved '${account.name}'"
        }
    }

    fun deleteAccount(account: SyncAccount) {
        viewModelScope.launch {
            repo.saveAccounts(repo.accounts.first().filterNot { it.id == account.id })
            secrets.removeAccountToken(account.id)
            // drop cached profiles from this account
            repo.saveCached(
                repo.cachedProfiles.first()
                    .filterNot { it.origin.startsWith("tabby:${account.id}:") }
            )
        }
    }

    /**
     * Tests host+token WITHOUT persisting anything (previous version saved a
     * half-configured account and could hang). Result goes to [onResult] so the
     * dialog can show errors inline instead of a hidden snackbar.
     */
    fun testAndList(
        name: String,
        host: String,
        token: String,
        onResult: (configs: List<RemoteConfigMeta>, error: String?) -> Unit,
    ) {
        viewModelScope.launch {
            _busy.value = true
            try {
                onResult(sync.listRemoteConfigs(host, token), null)
            } catch (e: Exception) {
                onResult(emptyList(), at.websters.tabbyandroid.data.sync.TabbySyncApiFactory.friendlyError(e))
            } finally {
                _busy.value = false
            }
        }
    }

    private suspend fun saveAccountInternal(account: SyncAccount) {
        val current = repo.accounts.first().toMutableList()
        val idx = current.indexOfFirst { it.id == account.id }
        if (idx >= 0) current[idx] = account else current.add(account)
        repo.saveAccounts(current)
    }

    /**
     * Push local state to the server. Explicit tap only - there is no
     * auto-upload anywhere in the app. Uploads this account's synced profiles
     * plus on-device profiles; unmanaged server entries are preserved.
     */
    fun upload(account: SyncAccount) {
        viewModelScope.launch {
            _busy.value = true
            _msg.value = null
            try {
                val cached = repo.cachedProfiles.first()
                val manual = repo.manualProfiles.first()
                val prefix = "tabby:${account.id}:"
                val payload = cached.filter { it.origin.startsWith(prefix) } + manual
                val stones = repo.currentTombstones()[account.id].orEmpty().toSet()
                if (payload.isEmpty() && stones.isEmpty()) {
                    _msg.value = "Nothing to upload for '${account.name}'"
                    return@launch
                }
                val vaultPw = VaultPassphrases.peek(account.id)
                    ?: secrets.getVaultPassphrase(account.id).takeIf { it.isNotBlank() }
                val r = sync.upload(account, payload, stones, vaultPw)
                if (r.ok) {
                    repo.clearTombstones(account.id, payload.map { it.id }.toSet() + stones)
                    saveAccountInternal(account.copy(lastSyncAtEpochMs = System.currentTimeMillis(), lastError = null))
                    _msg.value = "Uploaded ${r.uploaded} profiles" +
                        (if (r.removed > 0) " (removed ${r.removed})" else "") +
                        " to '${account.selectedConfigName ?: account.name}'"
                } else {
                    if (r.vaultLocked) VaultLocks.set(account.id)
                    saveAccountInternal(account.copy(lastError = r.error))
                    _msg.value = "Upload failed: ${r.error}"
                }
            } finally {
                _busy.value = false
            }
        }
    }

    fun getToken(accountId: String): String = secrets.getAccountToken(accountId)
    fun dismiss() { _msg.value = null }

    val allowScreen: StateFlow<Boolean> = repo.allowScreenCapture
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setAllowScreen(allow: Boolean) {
        viewModelScope.launch { repo.setAllowScreenCapture(allow) }
    }

    fun forgetHostKeys() {
        viewModelScope.launch {
            at.websters.tabbyandroid.data.local.SecureTokenStorage(getApplication()).clearHostKeys()
            _msg.value = "Saved host keys forgotten — servers will ask to verify again"
        }
    }
}
