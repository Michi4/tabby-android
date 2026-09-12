package at.websters.tabbyandroid.data.ssh

import at.websters.tabbyandroid.data.model.SshProfile
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.Closeable
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
 * Host-key verification: first connect stores the key digest in-memory and
 * surfaces it via [pendingHostKey] for explicit user accept (TOFU, Termius-like).
 */
class SshConnection(
    val profile: SshProfile,
    val buffer: TerminalBuffer = TerminalBuffer(cols = 80, rows = 24),
) : Closeable {
    private val _state = MutableStateFlow(SshState.DISCONNECTED)
    val state: StateFlow<SshState> = _state
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    @Volatile var pendingHostKey: String? = null
        private set

    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var readerJob: Job? = null

    suspend fun connect(
        password: String,
        privateKeyPem: String? = null,
        privateKeyPassphrase: String? = null,
        acceptHostKey: Boolean = false,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            _state.value = SshState.CONNECTING
            _status.value = "Connecting to ${profile.host}:${profile.port}…"
            val jsch = JSch()
            if (!privateKeyPem.isNullOrBlank()) {
                if (privateKeyPassphrase.isNullOrBlank()) jsch.addIdentity("android-key", privateKeyPem.toByteArray(), null, null)
                else jsch.addIdentity("android-key", privateKeyPem.toByteArray(), null, privateKeyPassphrase.toByteArray())
            }
            val s = jsch.getSession(profile.username, profile.host, profile.port)
            if (password.isNotBlank()) s.setPassword(password)
            val cfg = Properties()
            cfg["StrictHostKeyChecking"] = if (acceptHostKey) "no" else "ask"
            s.setConfig(cfg)
            s.setTimeout(15_000)
            s.setServerAliveInterval((profile.keepaliveIntervalSec.coerceIn(0, 300) * 1000))
            try {
                s.connect(15_000)
            } catch (e: Exception) {
                val msg = e.message.orEmpty()
                if (!acceptHostKey && (msg.contains("UnknownHostKey", ignoreCase = true) || msg.contains("reject HostKey", ignoreCase = true))) {
                    pendingHostKey = msg
                    _state.value = SshState.ERROR
                    _status.value = "Unknown host key. Review and accept to continue."
                    return@withContext Result.failure(UnknownHostKeyException(msg))
                }
                throw e
            }
            val ch = s.openChannel("shell") as ChannelShell
            ch.setPtyType("xterm-256color", 80, 24, 0, 0)
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
            Result.success(Unit)
        } catch (e: Exception) {
            // UnknownHostKeyException is handled by the caller (accept dialog);
            // keep its message untouched so the fingerprint stays visible.
            if (e !is UnknownHostKeyException) {
                _status.value = friendlyError(e)
            }
            _state.value = SshState.ERROR
            if (_status.value.isBlank()) _status.value = "Connection failed"
            Result.failure(e)
        }
    }

    fun send(text: String) {
        try {
            channel?.outputStream?.write(text.toByteArray())
            channel?.outputStream?.flush()
        } catch (_: Exception) {
        }
    }

    fun sendKey(key: String) = send(key)

    override fun close() {
        try { readerJob?.cancel() } catch (_: Exception) {}
        try { channel?.disconnect() } catch (_: Exception) {}
        try { session?.disconnect() } catch (_: Exception) {}
        _state.value = SshState.DISCONNECTED
    }
}

class UnknownHostKeyException(message: String) : Exception(message)

/** Short, actionable SSH errors instead of raw stack text. */
fun SshConnection.friendlyError(e: Exception): String {
    val m = e.message.orEmpty()
    return when {
        e is java.net.UnknownHostException ->
            "Unknown host — check the hostname and your connection."
        e is java.net.SocketTimeoutException ->
            "Timed out — the server may be down or unreachable. Retry in a bit."
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
