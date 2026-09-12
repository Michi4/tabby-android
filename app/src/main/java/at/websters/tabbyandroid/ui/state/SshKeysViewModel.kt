package at.websters.tabbyandroid.ui.state

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import at.websters.tabbyandroid.data.local.ProfileRepository
import at.websters.tabbyandroid.data.local.SecureTokenStorage
import at.websters.tabbyandroid.data.model.SshKeyMeta
import at.websters.tabbyandroid.data.ssh.SshKeyManager
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Local SSH private keys. Metadata in DataStore, key bytes encrypted in
 * [SecureTokenStorage]. Keys never leave the device (never synced/uploaded).
 */
class SshKeysViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ProfileRepository(app)
    private val secrets = SecureTokenStorage(app)

    val keys: StateFlow<List<SshKeyMeta>> = repo.sshKeys
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _busy = kotlinx.coroutines.flow.MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    suspend fun importKey(name: String, pem: String, passphrase: String): Result<SshKeyMeta> =
        withContext(Dispatchers.IO) {
            try {
                require(name.isNotBlank()) { "Give the key a name" }
                require(SshKeyManager.looksLikePem(pem)) { "That doesn't look like a private key (need a PEM block)" }
                SshKeyManager.validatePem(pem, passphrase)
                val meta = SshKeyMeta(id = "key:${UUID.randomUUID()}", name = name.trim())
                secrets.putSshKey(meta.id, pem, passphrase)
                repo.saveSshKeys(repo.sshKeys.first() + meta)
                Result.success(meta)
            } catch (e: Exception) {
                Result.failure(Exception(e.message ?: "Invalid key"))
            }
        }

    suspend fun generateKey(name: String): Result<Pair<SshKeyMeta, String>> =
        withContext(Dispatchers.IO) {
            try {
                require(name.isNotBlank()) { "Give the key a name" }
                val g = SshKeyManager.generateKey("tabby-android")
                val meta = SshKeyMeta(id = "key:${UUID.randomUUID()}", name = name.trim())
                secrets.putSshKey(meta.id, g.privatePem, "")
                repo.saveSshKeys(repo.sshKeys.first() + meta)
                Result.success(meta to g.publicOpenSsh)
            } catch (e: Exception) {
                Result.failure(Exception(e.message ?: "Key generation failed"))
            }
        }

    fun deleteKey(id: String) {
        viewModelScope.launch {
            repo.saveSshKeys(repo.sshKeys.first().filterNot { it.id == id })
            secrets.removeSshKey(id)
            // profiles pointing at the deleted key fall back to password auth
            repo.saveCached(repo.cachedProfiles.first().map {
                if (it.keyId == id) it.copy(keyId = null) else it
            })
            repo.saveManual(repo.manualProfiles.first().map {
                if (it.keyId == id) it.copy(keyId = null) else it
            })
        }
    }

    fun loadKey(id: String): Pair<String, String>? = secrets.getSshKey(id)
}
