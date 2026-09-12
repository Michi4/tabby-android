package at.websters.tabbyandroid.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
        /** Where to get a sync service (self-hosted Tabby Web), shown as a Learn-more link. */
        const val SYNC_DOCS_URL = "https://github.com/Eugeny/tabby-web"
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
            it[KEY_ACCOUNTS] = json.encodeToString(ListSerializer(SyncAccount.serializer()), accounts)
        }
    }

    suspend fun saveCached(profiles: List<SshProfile>) {
        appContext.tabbyStore.edit {
            it[KEY_CACHED] = json.encodeToString(ListSerializer(SshProfile.serializer()), profiles)
        }
    }

    suspend fun saveManual(profiles: List<SshProfile>) {
        appContext.tabbyStore.edit {
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
            it[KEY_GROUPS] = json.encodeToString(
                ListSerializer(at.websters.tabbyandroid.data.model.TabbyGroup.serializer()), groups
            )
        }
    }

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

    suspend fun currentAccounts(): List<SyncAccount> = accounts.first()
}
