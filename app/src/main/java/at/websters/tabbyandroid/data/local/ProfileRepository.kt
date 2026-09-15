package at.websters.tabbyandroid.data.local

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import at.websters.tabbyandroid.data.model.Pin
import at.websters.tabbyandroid.data.model.SshProfile
import at.websters.tabbyandroid.data.model.SyncAccount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val Context.tabbyStore by preferencesDataStore(name = "tabby_client")

/** Defaults for [ProfileRepository] terminal prefs (new tabs pick these up). */
object UiPrefsDefaults {
    const val FONT_SIZE = 13
    const val FOLLOW = true
    const val KEY_ROWS = 3
    const val FULLSCREEN = false
    const val PINCH_ZOOM = true
    const val SUGGESTIONS = true
    const val SCROLLBACK = 5000
    /** Cycler options for the scrollback setting. */
    val SCROLLBACK_OPTIONS = listOf(1000, 2000, 5000, 10000, 50000)
    /**
     * Practically unlimited (min 1 only guards against invalid zero/negative
     * sizes that crash text layout; 256sp already fills the screen).
     */
    const val FONT_MIN = 1
    const val FONT_MAX = 256
}

/** Snapshot of terminal defaults. */
data class UiPrefs(
    val fontSize: Int = UiPrefsDefaults.FONT_SIZE,
    val follow: Boolean = UiPrefsDefaults.FOLLOW,
    val keyRows: Int = UiPrefsDefaults.KEY_ROWS,
    val fullscreen: Boolean = UiPrefsDefaults.FULLSCREEN,
    val keyLayout: KeyLayout = KeyLayout(),
    val pinchZoom: Boolean = UiPrefsDefaults.PINCH_ZOOM,
    val suggestions: Boolean = UiPrefsDefaults.SUGGESTIONS,
    val scrollback: Int = UiPrefsDefaults.SCROLLBACK,
)

/** Clampers (pure, unit-tested): prefs storage can hold anything. */
fun sanitizeFontSize(sizeSp: Int): Int = sizeSp.coerceIn(UiPrefsDefaults.FONT_MIN, UiPrefsDefaults.FONT_MAX)

fun sanitizeKeyRows(rows: Int): Int = rows.coerceIn(0, 4)

fun sanitizeScrollback(n: Int): Int = n.coerceIn(1000, 50000)

/**
 * Single DataStore for sync accounts + cached + manual profiles.
 * Tokens themselves live in [SecureTokenStorage] (EncryptedSharedPreferences),
 * never in plain DataStore - best practice for secrets on Android.
 */
class ProfileRepository(private val appContext: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private val KEY_ACCOUNTS = stringPreferencesKey("sync_accounts_json")
        private val KEY_CACHED = stringPreferencesKey("cached_profiles_json")
        private val KEY_MANUAL = stringPreferencesKey("manual_profiles_json")
        private val KEY_TOMBSTONES = stringPreferencesKey("tombstones_json")
        private val KEY_SSH_KEYS = stringPreferencesKey("ssh_keys_json")
        private val KEY_PINS = stringPreferencesKey("pins_json")
        private val KEY_COLLAPSED = stringPreferencesKey("collapsed_json")
        private val KEY_GROUPS = stringPreferencesKey("groups_json")
        private val KEY_ALLOW_SCREEN = booleanPreferencesKey("allow_screen_capture")
        private val KEY_APP_LOCK = booleanPreferencesKey("app_lock")
        private val KEY_UI_FONT = intPreferencesKey("ui_font_size")
        private val KEY_UI_FOLLOW = booleanPreferencesKey("ui_follow")
        private val KEY_UI_ROWS = intPreferencesKey("ui_key_rows")
        private val KEY_UI_FULLSCREEN = booleanPreferencesKey("ui_fullscreen")
        private val KEY_UI_LAYOUT = stringPreferencesKey("ui_key_layout_json")
        private val KEY_UI_PINCH = booleanPreferencesKey("ui_pinch_zoom")
        private val KEY_UI_SUGGEST = booleanPreferencesKey("ui_suggestions")
        private val KEY_UI_SCROLLBACK = intPreferencesKey("ui_scrollback")
        private val KEY_TAB_SCROLLBACK = stringPreferencesKey("open_tabs_scrollback_json")
        private val KEY_UPDATE_CHECK = stringPreferencesKey("update_check_json")
        private val KEY_UPDATE_AUTO = booleanPreferencesKey("update_auto_download")
        private val KEY_VAULT_LOCK = stringPreferencesKey("vault_lock_json")
        private val KEY_VAULT_SEALED = stringPreferencesKey("vault_sealed_json")
        private val KEY_OPEN_TABS = stringPreferencesKey("open_tabs_json")
        private val KEY_REMOTE_HASH = stringPreferencesKey("remote_hash_json")
        /** Where to get a sync service (self-hosted Tabby Web), shown as a Learn-more link. */
        const val SYNC_DOCS_URL = "https://github.com/Eugeny/tabby-web"
        /** Vault lock modes: ask every time (default), remember, or biometric/PIN-guarded. */
        const val LOCK_SESSION = "session"
        const val LOCK_FOREVER = "forever"
        const val LOCK_GUARDED = "guarded"
    }

    /** Opt-in to screenshots/screen sharing (default off = FLAG_SECURE). */
    val allowScreenCapture: Flow<Boolean> = appContext.tabbyStore.data.map {
        it[KEY_ALLOW_SCREEN] ?: false
    }

    suspend fun setAllowScreenCapture(allow: Boolean) {
        appContext.tabbyStore.edit { it[KEY_ALLOW_SCREEN] = allow }
    }

    /**
     * App lock (biometrics or device PIN on every foregrounding).
     * Default off; unlocking your SSH keys warrants opting in.
     */
    val appLock: Flow<Boolean> = appContext.tabbyStore.data.map {
        it[KEY_APP_LOCK] ?: false
    }

    suspend fun setAppLock(locked: Boolean) {
        appContext.tabbyStore.edit { it[KEY_APP_LOCK] = locked }
    }

    /**
     * Terminal defaults for newly opened tabs (Settings → Terminal).
     * Open tabs keep their own runtime toggles; changing a default never
     * yanks an active session.
     */
    val uiFontSize: Flow<Int> = appContext.tabbyStore.data.map {
        sanitizeFontSize(it[KEY_UI_FONT] ?: UiPrefsDefaults.FONT_SIZE)
    }

    val uiFollow: Flow<Boolean> = appContext.tabbyStore.data.map {
        it[KEY_UI_FOLLOW] ?: UiPrefsDefaults.FOLLOW
    }

    val uiKeyRows: Flow<Int> = appContext.tabbyStore.data.map {
        sanitizeKeyRows(it[KEY_UI_ROWS] ?: UiPrefsDefaults.KEY_ROWS)
    }

    val uiFullscreen: Flow<Boolean> = appContext.tabbyStore.data.map {
        it[KEY_UI_FULLSCREEN] ?: UiPrefsDefaults.FULLSCREEN
    }

    val uiPinchZoom: Flow<Boolean> = appContext.tabbyStore.data.map {
        it[KEY_UI_PINCH] ?: UiPrefsDefaults.PINCH_ZOOM
    }

    val uiSuggestions: Flow<Boolean> = appContext.tabbyStore.data.map {
        it[KEY_UI_SUGGEST] ?: UiPrefsDefaults.SUGGESTIONS
    }

    val uiScrollback: Flow<Int> = appContext.tabbyStore.data.map {
        sanitizeScrollback(it[KEY_UI_SCROLLBACK] ?: UiPrefsDefaults.SCROLLBACK)
    }

    val uiKeyLayout: Flow<KeyLayout> = appContext.tabbyStore.data.map {
        it[KEY_UI_LAYOUT]?.let { raw ->
            runCatching { sanitizeKeyLayout(json.decodeFromString(KeyLayout.serializer(), raw)) }
                .getOrDefault(KeyLayout())
        } ?: KeyLayout()
    }

    suspend fun setUiFontSize(sizeSp: Int) {
        appContext.tabbyStore.edit { it[KEY_UI_FONT] = sanitizeFontSize(sizeSp) }
    }

    suspend fun setUiFollow(follow: Boolean) {
        appContext.tabbyStore.edit { it[KEY_UI_FOLLOW] = follow }
    }

    suspend fun setUiKeyRows(rows: Int) {
        appContext.tabbyStore.edit { it[KEY_UI_ROWS] = sanitizeKeyRows(rows) }
    }

    suspend fun setUiFullscreen(fullscreen: Boolean) {
        appContext.tabbyStore.edit { it[KEY_UI_FULLSCREEN] = fullscreen }
    }

    suspend fun setUiPinchZoom(enabled: Boolean) {
        appContext.tabbyStore.edit { it[KEY_UI_PINCH] = enabled }
    }

    suspend fun setUiSuggestions(enabled: Boolean) {
        appContext.tabbyStore.edit { it[KEY_UI_SUGGEST] = enabled }
    }

    suspend fun setUiScrollback(n: Int) {
        appContext.tabbyStore.edit { it[KEY_UI_SCROLLBACK] = sanitizeScrollback(n) }
    }

    suspend fun setUiKeyLayout(layout: KeyLayout) {
        val safe = sanitizeKeyLayout(layout)
        appContext.tabbyStore.edit {
            it.quarantineIfCorrupt(KEY_UI_LAYOUT) { raw ->
                json.decodeFromString(KeyLayout.serializer(), raw)
            }
            it[KEY_UI_LAYOUT] = json.encodeToString(KeyLayout.serializer(), safe)
        }
    }

    val accounts: Flow<List<SyncAccount>> = appContext.tabbyStore.data.map {
        it[KEY_ACCOUNTS]?.let { raw ->
            runCatching { json.decodeFromString(ListSerializer(SyncAccount.serializer()), raw) }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    val cachedProfiles: Flow<List<SshProfile>> = appContext.tabbyStore.data.map {
        it[KEY_CACHED]?.let { raw ->
            runCatching { json.decodeFromString(ListSerializer(SshProfile.serializer()), raw) }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    val manualProfiles: Flow<List<SshProfile>> = appContext.tabbyStore.data.map {
        it[KEY_MANUAL]?.let { raw ->
            runCatching { json.decodeFromString(ListSerializer(SshProfile.serializer()), raw) }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    suspend fun saveAccounts(accounts: List<SyncAccount>) {
        appContext.tabbyStore.edit {
            it.quarantineIfCorrupt(KEY_ACCOUNTS) { raw ->
                json.decodeFromString(ListSerializer(SyncAccount.serializer()), raw)
            }
            it[KEY_ACCOUNTS] = json.encodeToString(ListSerializer(SyncAccount.serializer()), accounts)
        }
    }

    suspend fun saveCached(profiles: List<SshProfile>) {
        appContext.tabbyStore.edit {
            it.quarantineIfCorrupt(KEY_CACHED) { raw ->
                json.decodeFromString(ListSerializer(SshProfile.serializer()), raw)
            }
            it[KEY_CACHED] = json.encodeToString(ListSerializer(SshProfile.serializer()), profiles)
        }
    }

    /**
     * Atomically removes a synced profile AND records its tombstone AND drops
     * dangling pin/open-tab refs — a single DataStore edit, so a crash can
     * neither resurrect the host without its tombstone nor keep the tombstone
     * without the delete.
     */
    suspend fun deleteCachedProfile(profileId: String, accountId: String?) {
        appContext.tabbyStore.edit { prefs ->
            prefs.quarantineIfCorrupt(KEY_CACHED) { raw ->
                json.decodeFromString(ListSerializer(SshProfile.serializer()), raw)
            }
            prefs.quarantineIfCorrupt(KEY_TOMBSTONES) { raw ->
                json.decodeFromString(MapSerializer(String.serializer(), ListSerializer(String.serializer())), raw)
            }
            val cached = prefs[KEY_CACHED]?.let { raw ->
                runCatching { json.decodeFromString(ListSerializer(SshProfile.serializer()), raw) }.getOrDefault(emptyList())
            } ?: emptyList()
            prefs[KEY_CACHED] = json.encodeToString(
                ListSerializer(SshProfile.serializer()),
                cached.filterNot { it.id == profileId },
            )
            if (accountId != null) {
                val stones = prefs[KEY_TOMBSTONES]?.let { raw ->
                    runCatching {
                        json.decodeFromString(MapSerializer(String.serializer(), ListSerializer(String.serializer())), raw)
                    }.getOrDefault(emptyMap())
                } ?: emptyMap()
                val mut = stones.toMutableMap()
                mut[accountId] = (mut[accountId].orEmpty() + profileId).distinct()
                prefs[KEY_TOMBSTONES] = json.encodeToString(
                    MapSerializer(String.serializer(), ListSerializer(String.serializer())), mut
                )
            }
            prefs.purgeProfileRefs(setOf(profileId))
        }
    }

    /**
     * Drops pin + open-tab refs for ids (call after a manual delete, which
     * has no tombstone). Shares one edit with the caller's own save.
     */
    suspend fun purgeProfileRefs(profileIds: Set<String>) {
        if (profileIds.isEmpty()) return
        appContext.tabbyStore.edit { prefs -> prefs.purgeProfileRefs(profileIds) }
    }

    /**
     * If [key] holds a value that no longer decodes, stash the raw blob under
     * "<key>.corrupt-bak" before the caller overwrites it. Reads elsewhere
     * fail open to empty (never crash), so without this a corrupt blob plus
     * any later save would silently vaporize user data. [parse] only attempts
     * a decode — the result is discarded.
     */
    private fun MutablePreferences.quarantineIfCorrupt(
        key: Preferences.Key<String>,
        parse: (String) -> Any?,
    ) {
        val raw = this[key] ?: return
        if (runCatching { parse(raw) }.isFailure) {
            this[stringPreferencesKey(key.name + ".corrupt-bak")] = raw
        }
    }

    private fun MutablePreferences.purgeProfileRefs(profileIds: Set<String>) {
        quarantineIfCorrupt(KEY_PINS) { raw ->
            json.decodeFromString(ListSerializer(Pin.serializer()), raw)
        }
        quarantineIfCorrupt(KEY_OPEN_TABS) { raw ->
            json.decodeFromString(ListSerializer(SshProfile.serializer()), raw)
        }
        quarantineIfCorrupt(KEY_TAB_SCROLLBACK) { raw ->
            json.decodeFromString(MapSerializer(String.serializer(), ListSerializer(String.serializer())), raw)
        }
        val pins = this[KEY_PINS]?.let { raw ->
            runCatching {
                json.decodeFromString(ListSerializer(Pin.serializer()), raw)
            }.getOrDefault(emptyList())
        } ?: emptyList()
        this[KEY_PINS] = json.encodeToString(
            ListSerializer(Pin.serializer()),
            pins.filterNot { it.kind == Pin.HOST && it.ref in profileIds },
        )
        val tabs = this[KEY_OPEN_TABS]?.let { raw ->
            runCatching { json.decodeFromString(ListSerializer(SshProfile.serializer()), raw) }.getOrDefault(emptyList())
        } ?: emptyList()
        this[KEY_OPEN_TABS] = json.encodeToString(
            ListSerializer(SshProfile.serializer()),
            tabs.filterNot { it.id in profileIds },
        )
        // Drop orphan scrollback (plaintext — may contain on-screen secrets)
        val sb = this[KEY_TAB_SCROLLBACK]?.let { raw ->
            runCatching {
                json.decodeFromString(MapSerializer(String.serializer(), ListSerializer(String.serializer())), raw)
            }.getOrDefault(emptyMap())
        } ?: emptyMap()
        this[KEY_TAB_SCROLLBACK] = json.encodeToString(
            MapSerializer(String.serializer(), ListSerializer(String.serializer())),
            sb.filterKeys { it !in profileIds },
        )
    }

    suspend fun saveManual(profiles: List<SshProfile>) {
        appContext.tabbyStore.edit {
            it.quarantineIfCorrupt(KEY_MANUAL) { raw ->
                json.decodeFromString(ListSerializer(SshProfile.serializer()), raw)
            }
            it[KEY_MANUAL] = json.encodeToString(ListSerializer(SshProfile.serializer()), profiles)
        }
    }

    /** Local SSH key metadata (private key bytes live encrypted in [SecureTokenStorage]). */
    val sshKeys: Flow<List<at.websters.tabbyandroid.data.model.SshKeyMeta>> =
        appContext.tabbyStore.data.map {
            it[KEY_SSH_KEYS]?.let { raw ->
                runCatching {
                    json.decodeFromString(
                        ListSerializer(at.websters.tabbyandroid.data.model.SshKeyMeta.serializer()), raw
                    )
                }.getOrDefault(emptyList())
            } ?: emptyList()
        }

    suspend fun saveSshKeys(keys: List<at.websters.tabbyandroid.data.model.SshKeyMeta>) {
        appContext.tabbyStore.edit {
            it.quarantineIfCorrupt(KEY_SSH_KEYS) { raw ->
                json.decodeFromString(
                    ListSerializer(at.websters.tabbyandroid.data.model.SshKeyMeta.serializer()), raw
                )
            }
            it[KEY_SSH_KEYS] = json.encodeToString(
                ListSerializer(at.websters.tabbyandroid.data.model.SshKeyMeta.serializer()), keys
            )
        }
    }

    /** Pinned hosts/folders in display order. Ids only, no secrets. */
    val pins: Flow<List<at.websters.tabbyandroid.data.model.Pin>> =
        appContext.tabbyStore.data.map {
            it[KEY_PINS]?.let { raw ->
                runCatching {
                    json.decodeFromString(
                        ListSerializer(at.websters.tabbyandroid.data.model.Pin.serializer()), raw
                    )
                }.getOrDefault(emptyList())
            } ?: emptyList()
        }

    suspend fun savePins(pins: List<at.websters.tabbyandroid.data.model.Pin>) {
        appContext.tabbyStore.edit {
            it.quarantineIfCorrupt(KEY_PINS) { raw ->
                json.decodeFromString(
                    ListSerializer(at.websters.tabbyandroid.data.model.Pin.serializer()), raw
                )
            }
            it[KEY_PINS] = json.encodeToString(
                ListSerializer(at.websters.tabbyandroid.data.model.Pin.serializer()), pins
            )
        }
    }

    /** Collapsed group ids (plus "__pins" for the pinned section). Plain lines, no generics hassle. */
    val collapsed: Flow<Set<String>> = appContext.tabbyStore.data.map {
        it[KEY_COLLAPSED]
            ?.split("\n")
            ?.map { id -> id.trim() }
            ?.filter { id -> id.isNotEmpty() }
            ?.toSet()
            ?: emptySet()
    }

    suspend fun saveCollapsed(ids: Set<String>) {
        appContext.tabbyStore.edit {
            it[KEY_COLLAPSED] = ids.joinToString("\n")
        }
    }

    /** Folder structures (id, name, parent) merged from all accounts. No secrets. */
    val groups: Flow<List<at.websters.tabbyandroid.data.model.TabbyGroup>> =
        appContext.tabbyStore.data.map {
            it[KEY_GROUPS]?.let { raw ->
                runCatching {
                    json.decodeFromString(
                        ListSerializer(at.websters.tabbyandroid.data.model.TabbyGroup.serializer()), raw
                    )
                }.getOrDefault(emptyList())
            } ?: emptyList()
        }

    suspend fun saveGroups(groups: List<at.websters.tabbyandroid.data.model.TabbyGroup>) {
        appContext.tabbyStore.edit {
            it.quarantineIfCorrupt(KEY_GROUPS) { raw ->
                json.decodeFromString(
                    ListSerializer(at.websters.tabbyandroid.data.model.TabbyGroup.serializer()), raw
                )
            }
            it[KEY_GROUPS] = json.encodeToString(
                ListSerializer(at.websters.tabbyandroid.data.model.TabbyGroup.serializer()), groups
            )
        }
    }

    /**
     * Vault lock mode per account: `session` (ask every time, memory only -
     * the default), `forever` (remember encrypted, no prompt), `guarded`
     * (biometrics/device PIN on every unlock). Ids only, no secrets.
     */
    val vaultLockModes: Flow<Map<String, String>> = appContext.tabbyStore.data.map {
        it[KEY_VAULT_LOCK]?.let { raw ->
            runCatching {
                json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), raw)
            }.getOrDefault(emptyMap())
        } ?: emptyMap()
    }

    suspend fun setVaultLockMode(accountId: String, mode: String) {
        val all = vaultLockModes.first().toMutableMap()
        all[accountId] = mode
        appContext.tabbyStore.edit {
            it.quarantineIfCorrupt(KEY_VAULT_LOCK) { raw ->
                json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), raw)
            }
            it[KEY_VAULT_LOCK] = json.encodeToString(MapSerializer(String.serializer(), String.serializer()), all)
        }
    }

    suspend fun currentVaultLockModes(): Map<String, String> = vaultLockModes.first()

    /** Drops a custom lock mode (falls back to ask-every-time). Used on forget/delete. */
    suspend fun clearVaultLockMode(accountId: String) {
        val all = vaultLockModes.first().toMutableMap()
        if (all.remove(accountId) != null) {
            appContext.tabbyStore.edit {
                it.quarantineIfCorrupt(KEY_VAULT_LOCK) { raw ->
                    json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), raw)
                }
                it[KEY_VAULT_LOCK] = json.encodeToString(MapSerializer(String.serializer(), String.serializer()), all)
            }
        }
    }

    /** Keystore-guarded vault blobs per account (ciphertext only, safe anywhere). */
    val vaultSealed: Flow<Map<String, String>> = appContext.tabbyStore.data.map {
        it[KEY_VAULT_SEALED]?.let { raw ->
            runCatching {
                json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), raw)
            }.getOrDefault(emptyMap())
        } ?: emptyMap()
    }

    suspend fun saveVaultSealed(accountId: String, blob: String?) {
        val all = vaultSealed.first().toMutableMap()
        if (blob.isNullOrBlank()) all.remove(accountId) else all[accountId] = blob
        appContext.tabbyStore.edit {
            it.quarantineIfCorrupt(KEY_VAULT_SEALED) { raw ->
                json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), raw)
            }
            it[KEY_VAULT_SEALED] = json.encodeToString(MapSerializer(String.serializer(), String.serializer()), all)
        }
    }

    suspend fun currentVaultSealed(): Map<String, String> = vaultSealed.first()

    /**
     * Tombstones: ids of synced profiles deleted on this device, per account.
     * Applied on upload (remote entry removed) and pruned on pull once the id
     * no longer exists remotely. Never contains secrets - ids only.
     */
    val tombstones: Flow<Map<String, List<String>>> = appContext.tabbyStore.data.map {
        it[KEY_TOMBSTONES]?.let { raw ->
            runCatching {
                json.decodeFromString(MapSerializer(String.serializer(), ListSerializer(String.serializer())), raw)
            }.getOrDefault(emptyMap())
        } ?: emptyMap()
    }

    suspend fun currentTombstones(): Map<String, List<String>> = tombstones.first()

    private suspend fun saveTombstones(all: Map<String, List<String>>) {
        appContext.tabbyStore.edit {
            it.quarantineIfCorrupt(KEY_TOMBSTONES) { raw ->
                json.decodeFromString(MapSerializer(String.serializer(), ListSerializer(String.serializer())), raw)
            }
            it[KEY_TOMBSTONES] = json.encodeToString(
                MapSerializer(String.serializer(), ListSerializer(String.serializer())), all
            )
        }
    }

    suspend fun addTombstone(accountId: String, profileId: String) {
        val all = currentTombstones().toMutableMap()
        all[accountId] = (all[accountId].orEmpty() + profileId).distinct()
        saveTombstones(all)
    }

    /** Keeps only tombstones whose ids are still present remotely (drops moot ones). */
    suspend fun retainTombstones(accountId: String, idsStillRemote: Set<String>) {
        val all = currentTombstones().toMutableMap()
        val kept = all[accountId].orEmpty().filter { it in idsStillRemote }
        if (kept.isEmpty()) all.remove(accountId) else all[accountId] = kept
        saveTombstones(all)
    }

    /** Clears tombstones that were just uploaded (or are otherwise resolved). */
    suspend fun clearTombstones(accountId: String, ids: Set<String>) {
        val all = currentTombstones().toMutableMap()
        val kept = all[accountId].orEmpty().filter { it !in ids }
        if (kept.isEmpty()) all.remove(accountId) else all[accountId] = kept
        saveTombstones(all)
    }

    /** Drops every tombstone for an account (used on account delete). */
    suspend fun clearAllTombstones(accountId: String) {
        val all = currentTombstones().toMutableMap()
        if (all.remove(accountId) != null) saveTombstones(all)
    }

    suspend fun currentAccounts(): List<SyncAccount> = accounts.first()

    /**
     * SHA-256 of the last remote config content seen per account (pull or
     * upload). Lets upload detect "server changed since your last pull"
     * instead of blindly last-write-winning over desktop edits. Hashes only,
     * never content.
     */
    val remoteHashes: Flow<Map<String, String>> = appContext.tabbyStore.data.map {
        it[KEY_REMOTE_HASH]?.let { raw ->
            runCatching {
                json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), raw)
            }.getOrDefault(emptyMap())
        } ?: emptyMap()
    }

    suspend fun currentRemoteHashes(): Map<String, String> = remoteHashes.first()

    suspend fun saveRemoteHash(accountId: String, hash: String) {
        val all = currentRemoteHashes().toMutableMap()
        all[accountId] = hash
        appContext.tabbyStore.edit {
            it.quarantineIfCorrupt(KEY_REMOTE_HASH) { raw ->
                json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), raw)
            }
            it[KEY_REMOTE_HASH] = json.encodeToString(MapSerializer(String.serializer(), String.serializer()), all)
        }
    }

    suspend fun clearRemoteHash(accountId: String) {
        val all = currentRemoteHashes().toMutableMap()
        if (all.remove(accountId) != null) {
            appContext.tabbyStore.edit {
                it.quarantineIfCorrupt(KEY_REMOTE_HASH) { raw ->
                    json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), raw)
                }
                it[KEY_REMOTE_HASH] = json.encodeToString(MapSerializer(String.serializer(), String.serializer()), all)
            }
        }
    }

    /**
     * Open terminal tabs (profiles only, never secrets) so returning to the
     * app restores the tab strip — even after process death. Connections
     * themselves restart as disconnected tabs with one-tap reconnect (creds
     * come from encrypted storage, never from here).
     */
    val openTabs: Flow<List<SshProfile>> = appContext.tabbyStore.data.map {
        it[KEY_OPEN_TABS]?.let { raw ->
            runCatching { json.decodeFromString(ListSerializer(SshProfile.serializer()), raw) }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    suspend fun saveOpenTabs(profiles: List<SshProfile>) {
        appContext.tabbyStore.edit {
            it.quarantineIfCorrupt(KEY_OPEN_TABS) { raw ->
                json.decodeFromString(ListSerializer(SshProfile.serializer()), raw)
            }
            it[KEY_OPEN_TABS] = json.encodeToString(ListSerializer(SshProfile.serializer()), profiles.take(20))
        }
    }

    /**
     * Last visible lines per open tab (plain text, capped) so a restored tab
     * reopens with its recent history after process death. Live tabs keep
     * their full buffer; this is only the restore seed.
     */
    val openTabScrollback: Flow<Map<String, List<String>>> = appContext.tabbyStore.data.map {
        it[KEY_TAB_SCROLLBACK]?.let { raw ->
            runCatching {
                json.decodeFromString(MapSerializer(String.serializer(), ListSerializer(String.serializer())), raw)
            }.getOrDefault(emptyMap())
        } ?: emptyMap()
    }

    suspend fun saveOpenTabScrollback(linesByProfile: Map<String, List<String>>) {
        // Filter obvious secret-bearing lines (reuse same heuristic as cmd history) — scrollback is plaintext
        val filtered = linesByProfile.mapValues { (_, lines) ->
            lines.filterNot { isSensitiveCommand(it) }
        }
        val capped = filtered.entries.take(10)
            .associate { (k, v) -> k to v.takeLast(200) }
        appContext.tabbyStore.edit {
            it.quarantineIfCorrupt(KEY_TAB_SCROLLBACK) { raw ->
                json.decodeFromString(MapSerializer(String.serializer(), ListSerializer(String.serializer())), raw)
            }
            it[KEY_TAB_SCROLLBACK] = json.encodeToString(
                MapSerializer(String.serializer(), ListSerializer(String.serializer())), capped
            )
        }
    }

    val updateAutoDownload: Flow<Boolean> = appContext.tabbyStore.data.map {
        it[KEY_UPDATE_AUTO] ?: true
    }

    suspend fun setUpdateAutoDownload(auto: Boolean) {
        appContext.tabbyStore.edit { it[KEY_UPDATE_AUTO] = auto }
    }

    /** Last update check (epoch ms) + newest known release, if any. */
    val updateCheck: Flow<Pair<Long, at.websters.tabbyandroid.data.update.ReleaseInfo?>> =
        appContext.tabbyStore.data.map {
            at.websters.tabbyandroid.data.update.parseUpdateCheck(it[KEY_UPDATE_CHECK])
        }

    suspend fun saveUpdateCheck(atEpochMs: Long, info: at.websters.tabbyandroid.data.update.ReleaseInfo?) {
        val js = info?.let {
            json.encodeToString(at.websters.tabbyandroid.data.update.ReleaseInfo.serializer(), it)
        }.orEmpty()
        appContext.tabbyStore.edit {
            it.quarantineIfCorrupt(KEY_UPDATE_CHECK) { raw ->
                val sep = raw.indexOf('|')
                require(sep >= 0) { "no pipe" }
                raw.substring(0, sep).toLong()
                val tail = raw.substring(sep + 1)
                if (tail.isNotBlank()) json.decodeFromString(
                    at.websters.tabbyandroid.data.update.ReleaseInfo.serializer(), tail
                )
                raw
            }
            it[KEY_UPDATE_CHECK] = "$atEpochMs|$js"
        }
    }
}
