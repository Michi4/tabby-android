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
import at.websters.tabbyandroid.data.sync.SyncMerge
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
            repo.purgeProfileRefs(setOf(profile.id))
        } else {
            // Single-edit delete + tombstone + ref purge: crash-safe, no resurrection.
            repo.deleteCachedProfile(
                profile.id,
                TabbyYamlSerializer.accountIdFromOrigin(profile.origin),
            )
        }
        // No orphan secrets or session state for a deleted host.
        secrets.removeSshPassword(profile.id)
        at.websters.tabbyandroid.ui.screens.SessionPasswords.take(profile.id)
        at.websters.tabbyandroid.ui.screens.SessionKeys.take(profile.id)
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
                val existingCached = repo.cachedProfiles.first()
                val existingGroups = repo.groups.first()
                val pulls = mutableListOf<SyncMerge.AccountPull>()
                // keep manual untouched; update only account slices that synced
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
                        pulls += SyncMerge.AccountPull(
                            accountId = acc.id,
                            ok = true,
                            profiles = r.profiles,
                            groups = r.groups.map { it.copy(ownerAccountId = acc.id) },
                        )
                        // Drop tombstones the server no longer has (deleted on desktop too).
                        repo.retainTombstones(acc.id, r.profiles.map { it.id }.toSet())
                        VaultLocks.clear(acc.id)
                        acc.copy(lastSyncAtEpochMs = System.currentTimeMillis(), lastError = null)
                    } else {
                        pulls += SyncMerge.AccountPull(acc.id, ok = false)
                        if (r.vaultLocked) VaultLocks.set(acc.id)
                        errors += "${acc.name}: ${r.error}"
                        acc.copy(lastError = r.error)
                    }
                }
                val merged = SyncMerge.merge(existingCached, existingGroups, pulls)
                repo.saveAccounts(updatedAccounts)
                repo.saveCached(merged.profiles)
                repo.saveGroups(merged.groups)
                _msg.value = if (errors.isEmpty()) {
                    if (merged.profiles.isEmpty()) "Synced — remote config is empty ({}). Add SSH profiles on desktop first."
                    else "Synced ${merged.profiles.size} profiles"
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
            // back to ask-every-time: a stale forever/guarded mode with no
            // secret would otherwise fail every auto-read as locked
            repo.clearVaultLockMode(account.id)
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
    data class Tab(
        val id: String = UUID.randomUUID().toString(),
        val profile: SshProfile,
        val conn: at.websters.tabbyandroid.data.ssh.TerminalConnection,
    )

    private val repo = ProfileRepository(app)
    private val secrets = at.websters.tabbyandroid.data.local.SecureTokenStorage(app)

    /**
     * Fire-and-forget pref write that can never crash the app: a settings
     * toggle must never take the process down, even on disk I/O failure.
     */
    private fun persist(block: suspend () -> Unit) {
        viewModelScope.launch { runCatching { block() } }
    }

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
        combine(
            repo.uiFontSize, repo.uiFollow, repo.uiKeyRows,
            repo.uiFullscreen, repo.uiKeyLayout,
        ) { fontSize, follow, keyRows, fullscreen, keyLayout ->
            // nested: this coroutines version has no 6+ flow typed overload
            at.websters.tabbyandroid.data.local.UiPrefs(
                fontSize, follow, keyRows, fullscreen, keyLayout
            )
        },
        repo.uiPinchZoom,
        repo.uiSuggestions,
        repo.uiScrollback,
    ) { prefs, pinchZoom, suggestions, scrollback ->
        prefs.copy(pinchZoom = pinchZoom, suggestions = suggestions, scrollback = scrollback)
    }.stateIn(
        viewModelScope, SharingStarted.Eagerly,
        at.websters.tabbyandroid.data.local.UiPrefs(),
    )

    fun setUiFontSize(sizeSp: Int) {
        persist { repo.setUiFontSize(sizeSp) }
    }

    fun setUiFollow(follow: Boolean) {
        persist { repo.setUiFollow(follow) }
    }

    fun setUiKeyRows(rows: Int) {
        persist { repo.setUiKeyRows(rows) }
    }

    fun setUiFullscreen(fullscreen: Boolean) {
        persist { repo.setUiFullscreen(fullscreen) }
    }

    fun setUiPinchZoom(enabled: Boolean) {
        persist { repo.setUiPinchZoom(enabled) }
    }

    fun setUiSuggestions(enabled: Boolean) {
        persist { repo.setUiSuggestions(enabled) }
    }

    fun setUiScrollback(n: Int) {
        persist { repo.setUiScrollback(n) }
    }

    // ---- command history (frequency-ranked suggestions) + macros ----
    // Encrypted at rest; synchronous tiny reads/writes, safe on the UI thread.

    private val _historyTick = MutableStateFlow(0)
    /** Bumped on every record/clear so suggestion rows refresh. */
    val historyTick: StateFlow<Int> = _historyTick

    fun loadHistory(): List<at.websters.tabbyandroid.data.local.CmdEntry> =
        secrets.getCommandHistory()

    fun recordCommand(cmd: String) {
        val updated = at.websters.tabbyandroid.data.local.recordCommand(loadHistory(), cmd)
        secrets.saveCommandHistory(updated)
        _historyTick.value += 1
    }

    fun clearHistory() {
        secrets.clearCommandHistory()
        _historyTick.value += 1
    }

    private val _macrosTick = MutableStateFlow(0)
    val macrosTick: StateFlow<Int> = _macrosTick

    fun loadMacros(): List<at.websters.tabbyandroid.data.local.Macro> = secrets.getMacros()

    fun addMacro(name: String, command: String) {
        if (name.isBlank() || command.isBlank()) return
        secrets.saveMacros(loadMacros() + at.websters.tabbyandroid.data.local.Macro(name.trim(), command))
        _macrosTick.value += 1
    }

    fun deleteMacro(name: String) {
        secrets.saveMacros(loadMacros().filterNot { it.name == name })
        _macrosTick.value += 1
    }

    fun setUiKeyLayout(layout: at.websters.tabbyandroid.data.local.KeyLayout) {
        persist { repo.setUiKeyLayout(layout) }
    }

    init {
        // Restore previously open tabs (as disconnected tabs — creds reload
        // from encrypted storage on reconnect, never from the tab record).
        // Seeded with the last visible lines so recent history survives death.
        viewModelScope.launch {
            val saved = repo.openTabs.first()
            if (saved.isNotEmpty() && _tabs.value.isEmpty()) {
                val knownHosts = java.io.File(
                    getApplication<Application>().filesDir,
                    at.websters.tabbyandroid.data.ssh.KNOWN_HOSTS_NAME,
                )
                val scrollbacks = repo.openTabScrollback.first()
                _tabs.value = saved.map { p ->
                    val conn: at.websters.tabbyandroid.data.ssh.TerminalConnection =
                        if (p.id.startsWith(at.websters.tabbyandroid.data.ssh.DEMO_SHELL_PREFIX)) {
                            at.websters.tabbyandroid.data.ssh.LocalShellConnection(p)
                        } else {
                            SshConnection(p, knownHostsFile = knownHosts)
                        }
                    scrollbacks[p.id]
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { lines ->
                            conn.buffer.feed((lines.joinToString("\r\n") + "\r\n").toByteArray(Charsets.UTF_8))
                        }
                    Tab(profile = p, conn = conn)
                }
                _active.value = _tabs.value.lastOrNull()?.id
            }
        }
    }

    private fun persistTabs() {
        viewModelScope.launch {
            repo.saveOpenTabs(_tabs.value.map { it.profile })
            repo.saveOpenTabScrollback(
                _tabs.value.associate { it.profile.id to it.conn.buffer.lastLines(200) }
            )
        }
    }

    fun open(profile: SshProfile): String {
        val knownHosts = java.io.File(
            getApplication<Application>().filesDir,
            at.websters.tabbyandroid.data.ssh.KNOWN_HOSTS_NAME,
        )
        val tab = Tab(profile = profile, conn = SshConnection(profile, knownHostsFile = knownHosts))
        _tabs.value = _tabs.value + tab
        _active.value = tab.id
        persistTabs()
        return tab.id
    }

    fun openQuick(query: String): String = open(QuickConnectParser.parse(query))

    /** Opens the on-device demo shell (no server, no sync needed). */
    fun openDemoShell(): String {
        val existing = _tabs.value.find { it.profile.id.startsWith(at.websters.tabbyandroid.data.ssh.DEMO_SHELL_PREFIX) }
        if (existing != null) {
            _active.value = existing.id
            return existing.id
        }
        val profile = at.websters.tabbyandroid.data.ssh.demoShellProfile()
        val tab = Tab(profile = profile, conn = at.websters.tabbyandroid.data.ssh.LocalShellConnection(profile))
        _tabs.value = _tabs.value + tab
        _active.value = tab.id
        persistTabs()
        return tab.id
    }

    fun select(id: String) { _active.value = id }

    fun close(id: String) {
        _tabs.value.find { it.id == id }?.let {
            it.conn.close()
            at.websters.tabbyandroid.ui.screens.SessionPasswords.take(it.profile.id)
            at.websters.tabbyandroid.ui.screens.SessionKeys.take(it.profile.id)
        }
        _tabs.value = _tabs.value.filterNot { it.id == id }
        if (_active.value == id) _active.value = _tabs.value.lastOrNull()?.id
        persistTabs()
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

    /** Fire-and-forget pref write that can never crash the app (see tabs VM). */
    private fun persist(block: suspend () -> Unit) {
        viewModelScope.launch { runCatching { block() } }
    }

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
            // drop cached profiles from this account (collect ids first for secret purge)
            val removedIds = repo.cachedProfiles.first()
                .filter { it.origin.startsWith("tabby:${account.id}:") }
                .map { it.id }
                .toSet()
            repo.saveAccounts(repo.accounts.first().filterNot { it.id == account.id })
            secrets.removeAccountToken(account.id)
            secrets.removeVaultPassphrase(account.id)
            removedIds.forEach { secrets.removeSshPassword(it) }
            repo.saveCached(
                repo.cachedProfiles.first()
                    .filterNot { it.id in removedIds }
            )
            repo.saveVaultSealed(account.id, null)
            repo.clearVaultLockMode(account.id)
            repo.clearAllTombstones(account.id)
            repo.clearRemoteHash(account.id)
            repo.purgeProfileRefs(removedIds)
            at.websters.tabbyandroid.data.sync.VaultPassphrases.clear(account.id)
            at.websters.tabbyandroid.data.sync.VaultLocks.clear(account.id)
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
    private val _forceUpload = MutableStateFlow<SyncAccount?>(null)
    /** Account whose server config changed since our pull — ask before overwriting. */
    val forceUpload: StateFlow<SyncAccount?> = _forceUpload

    fun dismissForceUpload() { _forceUpload.value = null }

    fun upload(account: SyncAccount, force: Boolean = false) {
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
                val r = sync.upload(account, payload, stones, vaultPw, force)
                if (r.ok) {
                    repo.clearTombstones(account.id, payload.map { it.id }.toSet() + stones)
                    saveAccountInternal(account.copy(lastSyncAtEpochMs = System.currentTimeMillis(), lastError = null))
                    _msg.value = "Uploaded ${r.uploaded} profiles" +
                        (if (r.removed > 0) " (removed ${r.removed})" else "") +
                        " to '${account.selectedConfigName ?: account.name}'"
                } else if (r.remoteChanged && !force) {
                    // Nothing uploaded: let the user decide after seeing the warning.
                    _forceUpload.value = account
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
        persist { repo.setAllowScreenCapture(allow) }
    }

    val appLock: StateFlow<Boolean> = repo.appLock
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setAppLock(locked: Boolean) {
        persist { repo.setAppLock(locked) }
    }

    fun forgetHostKeys() {
        viewModelScope.launch {
            if (at.websters.tabbyandroid.data.local.SecureTokenStorage(getApplication()).clearHostKeys()) {
                _msg.value = "Saved host keys forgotten — servers will ask to verify again"
            } else {
                _msg.value = "Could not delete saved host keys — try again"
            }
        }
    }
}
