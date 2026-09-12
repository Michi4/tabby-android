package at.websters.tabbyandroid.data.ssh

import at.websters.tabbyandroid.data.model.SshProfile
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SshState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

/**
 * One live SSH shell. All blocking JSch I/O runs on Dispatchers.IO.
 *
 * Host-key verification (TOFU with pinning, Termius-like):
 * - known hosts live in an OpenSSH-format `known_hosts` file ([knownHostsFile]);
 * - unknown key -> [pendingHostKey] (type + SHA256 fingerprint) for explicit
 *   user accept; accepting appends to the file, so later connects are verified;
 * - a CHANGED key hard-fails (no accept path) -> possible MITM.
 */
class SshConnection(
    val profile: SshProfile,
    val buffer: TerminalBuffer = TerminalBuffer(cols = 80, rows = 24),
    val knownHostsFile: File? = null,
) : Closeable {
    private val _state = MutableStateFlow(SshState.DISCONNECTED)
    val state: StateFlow<SshState> = _state
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    @Volatile var pendingHostKey: String? = null
        private set

    @Volatile private var pendingKey: HostKey? = null

    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var readerJob: Job? = null
    /** Our end of the stdin pipe (canonical JSch shell input pattern). */
    private var shellInput: java.io.PipedOutputStream? = null

    suspend fun connect(
        password: String,
        privateKeyPem: String? = null,
        privateKeyPassphrase: String? = null,
        acceptHostKey: Boolean = false,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        for (attempt in 0 until 2) {
            try {
                openSession(password, privateKeyPem, privateKeyPassphrase)
                return@withContext Result.success(Unit)
            } catch (e: UnknownHostKeyException) {
                if (acceptHostKey && attempt == 0 && savePendingKey()) continue
                return@withContext Result.failure(e)
            } catch (e: Exception) {
                _state.value = SshState.ERROR
                _status.value = friendlyError(e)
                return@withContext Result.failure(e)
            }
        }
        _state.value = SshState.ERROR
        if (_status.value.isBlank()) _status.value = "Connection failed"
        Result.failure(Exception("Connection failed"))
    }

    /** One connection attempt (blocking). Throws on any failure. */
    private fun openSession(password: String, privateKeyPem: String?, privateKeyPassphrase: String?) {
        _state.value = SshState.CONNECTING
        _status.value = "Connecting to ${profile.host}:${profile.port}…"
        val jsch = JSch()
        knownHostsFile
            ?.takeIf { it.exists() && it.length() > 0 }
            ?.let { jsch.setKnownHosts(it.absolutePath) }
        if (!privateKeyPem.isNullOrBlank()) {
            if (privateKeyPassphrase.isNullOrBlank()) jsch.addIdentity("android-key", privateKeyPem.toByteArray(), null, null)
            else jsch.addIdentity("android-key", privateKeyPem.toByteArray(), null, privateKeyPassphrase.toByteArray())
        }
        val s = jsch.getSession(profile.username, profile.host, profile.port)
        if (password.isNotBlank()) s.setPassword(password)
        val cfg = Properties()
        cfg["StrictHostKeyChecking"] = "ask"
        s.setConfig(cfg)
        s.setTimeout(15_000)
        s.setServerAliveInterval((profile.keepaliveIntervalSec.coerceIn(0, 300) * 1000))
        try {
            s.connect(15_000)
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            if (msg.contains("UnknownHostKey", ignoreCase = true) || msg.contains("reject HostKey", ignoreCase = true)) {
                val hk = runCatching { s.hostKey }.getOrNull()
                pendingKey = hk
                pendingHostKey = formatHostKey(profile.host, profile.port, hk) ?: msg
                _state.value = SshState.ERROR
                _status.value = "Unknown host key. Review and accept to continue."
                throw UnknownHostKeyException(pendingHostKey!!)
            }
            throw e
        }
        val ch = s.openChannel("shell") as ChannelShell
        ch.setPtyType("xterm-256color", 80, 24, 0, 0)
        // canonical stdin wiring: our pipe feeds the remote shell (getOutputStream
        // is unreliable across JSch versions for interactive shells)
        val pipeIn = java.io.PipedInputStream(64 * 1024)
        shellInput = java.io.PipedOutputStream(pipeIn)
        ch.setInputStream(pipeIn)
        ch.connect(10_000)
        session = s
        channel = ch
        _state.value = SshState.CONNECTED
        _status.value = "Connected"
        val input = ch.inputStream
        readerJob = CoroutineScope(Dispatchers.IO).launch {
            val buf = ByteArray(8192)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) buffer.feed(buf, 0, n)
                }
            } catch (_: Exception) {
            } finally {
                _state.value = SshState.DISCONNECTED
                _status.value = "Disconnected"
            }
        }
    }

    /** Appends the accepted key to known_hosts (OpenSSH format). */
    private fun savePendingKey(): Boolean {
        val hk = pendingKey ?: return false
        val f = knownHostsFile ?: return false
        return runCatching {
            val hostPart = if (profile.port == 22) profile.host else "[${profile.host}]:${profile.port}"
            f.parentFile?.mkdirs()
            // HostKey.key is already base64 in JSch - store verbatim (OpenSSH line format)
            f.appendText("$hostPart ${hk.type} ${hk.key}\n")
            pendingKey = null
            true
        }.getOrDefault(false)
    }

    fun send(text: String) {
        try {
            shellInput?.write(text.toByteArray())
            shellInput?.flush()
        } catch (_: Exception) {
        }
    }

    fun sendKey(key: String) = send(key)

    override fun close() {
        try { readerJob?.cancel() } catch (_: Exception) {}
        try { shellInput?.close() } catch (_: Exception) {}
        try { channel?.disconnect() } catch (_: Exception) {}
        try { session?.disconnect() } catch (_: Exception) {}
        _state.value = SshState.DISCONNECTED
    }
}

class UnknownHostKeyException(message: String) : Exception(message)

/** `SHA256:<base64-nopad>` fingerprint over the raw key blob (base64), OpenSSH display format. */
fun sha256Fingerprint(keyB64: String): String {
    val blob = java.util.Base64.getDecoder().decode(keyB64.trim())
    val digest = MessageDigest.getInstance("SHA-256").digest(blob)
    return "SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(digest)
}

fun formatHostKey(host: String, port: Int, hk: HostKey?): String? {
    if (hk == null) return null
    return "Host key for $host:$port\nType: ${hk.type}\nFingerprint: ${sha256Fingerprint(hk.key)}"
}

/** Short, actionable SSH errors instead of raw stack text. */
fun SshConnection.friendlyError(e: Exception): String {
    val m = e.message.orEmpty()
    return when {
        e is java.net.UnknownHostException ->
            "Unknown host — check the hostname and your connection."
        e is java.net.SocketTimeoutException ->
            "Timed out — the server may be down or unreachable. Retry in a bit."
        m.contains("has been changed", ignoreCase = true) ->
            "HOST KEY CHANGED — the server's key differs from the saved one. " +
                "Possible attack: not connecting. If the server was reinstalled, " +
                "use Sync → Forget saved host keys, then reconnect."
        m.contains("ECONNREFUSED", ignoreCase = true) || m.contains("Connection refused", ignoreCase = true) ->
            "Connection refused — is SSH running on ${profile.host}:${profile.port}?"
        m.contains("Auth fail", ignoreCase = true) ->
            "Authentication failed — wrong password, key, or username."
        m.contains("Algorithm negotiation fail", ignoreCase = true) -> {
            val offered = Regex("serverProposal=\"([^\"]*)\"").find(m)?.groupValues?.getOrNull(1)
            "No shared SSH algorithms with this server." +
                (if (offered.isNullOrBlank()) "" else " Server only offers: $offered")
        }
        else -> m.ifBlank { "Connection failed" }.take(300)
    }
}
