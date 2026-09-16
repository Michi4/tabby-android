package at.websters.tabbyandroid.data.ssh

import at.websters.tabbyandroid.data.model.SshProfile
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SshState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

/** Name of the OpenSSH known_hosts file JSch enforces (under app filesDir). */
const val KNOWN_HOSTS_NAME = "known_hosts"

/**
 * One live SSH shell. All blocking JSch I/O runs on Dispatchers.IO.
 *
 * Host-key verification (TOFU with pinning, Termius-like):
 * - known hosts live in an OpenSSH-format `known_hosts` file ([knownHostsFile]);
 * - unknown key -> [pendingHostKey] (type + SHA256 fingerprint) for explicit
 *   user accept; accepting saves the pin, so later connects are verified;
 * - a CHANGED key stages the new key plus the old fingerprint for an
 *   explicit warning dialog — accept only when the server was knowingly
 *   reinstalled (possible MITM otherwise); accepting REPLACES the pin.
 */
class SshConnection(
    override val profile: SshProfile,
    override val buffer: TerminalBuffer = TerminalBuffer(cols = 80, rows = 24),
    val knownHostsFile: File? = null,
) : TerminalConnection {
    private val _state = MutableStateFlow(SshState.DISCONNECTED)
    override val state: StateFlow<SshState> = _state
    private val _status = MutableStateFlow("")
    override val status: StateFlow<String> = _status
    private val _forwardStatus = MutableStateFlow("")
    override val forwardStatus: StateFlow<String> = _forwardStatus
    private val _forwardsOn = MutableStateFlow(true)
    override val forwardsOn: StateFlow<Boolean> = _forwardsOn

    @Volatile override var pendingHostKey: String? = null
        private set

    /** True when the pending key REPLACES a previously saved one (possible MITM). */
    @Volatile override var pendingHostKeyChanged: Boolean = false
        private set

    @Volatile private var pendingKey: HostKey? = null

    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var readerJob: Job? = null
    /** Our end of the stdin pipe (canonical JSch shell input pattern). */
    private var shellInput: java.io.PipedOutputStream? = null
    /**
     * Last known viewport size. The UI keeps this current (font, key rows,
     * keyboard, fullscreen, rotation) so a NEW channel opens at the right
     * size and a LIVE channel gets SIGWINCH via [setPtySize].
     */
    @Volatile private var ptyCols = 80
    @Volatile private var ptyRows = 24

    /**
     * SSH input is shared by the UI thread (typing, paste, fill, key row)
     * and by the reader thread (terminal replies to DSR/CPR). Serialize all
     * pipe writes so a single logical write+flush cannot interleave.
     */
    private val ioLock = Any()

    /**
     * JSch channel requests must not run on arbitrary UI threads while shell
     * output is being written: a main-thread window-change racing channel
     * data corrupted the outgoing stream (server saw an unknown packet and
     * closed the session). Keep those side effects on one worker.
     *
     * Window-change requests are additionally COALESCEDED: terminal
     * viewport churn (keyboard slide animation frames, rotation, font
     * changes) would otherwise send a SIGWINCH per frame, and the server's
     * line editor reprints the prompt + current line on a fresh line for
     * each one — typing one word then fills scrollback with duplicated
     * prompts. Isolated changes (keyboard toggled after idle) go out
     * INSTANTLY so the terminal never freezes ~500ms; bursts collapse into
     * one leading + one settled request.
     */
    private val networkExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "tabby-ssh-network").apply {
            isDaemon = true
        }
    }
    @Volatile private var networkClosed = false
    @Volatile private var pendingResize: ScheduledFuture<*>? = null
    @Volatile private var lastSentCols = -1
    @Volatile private var lastSentRows = -1
    @Volatile private var lastResizeSentAt = 0L

    private fun writeStdin(bytes: ByteArray) {
        val input = shellInput ?: return
        synchronized(ioLock) {
            try {
                input.write(bytes)
                input.flush()
            } catch (_: Exception) {
            }
        }
    }

    override suspend fun connect(
        password: String,
        privateKeyPem: String?,
        privateKeyPassphrase: String?,
        acceptHostKey: Boolean,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        pendingHostKey = null
        pendingHostKeyChanged = false
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
            // Dead-peer detection: after this many unanswered keepalives JSch
            // drops the session instead of lingering as a zombie (e.g. USB
            // tethering drops, captive portals). The reader then reports
            // DISCONNECTED and the UI offers reconnect instead of silence.
            s.setServerAliveCountMax(3)
        try {
            s.connect(15_000)
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            if (msg.contains("has been changed", ignoreCase = true)) {
                // saved pin differs from what the server offers now: stage the
                // new key plus the old fingerprint so the UI can show a real
                // review dialog (accept only if YOU reinstalled the server).
                val hk = runCatching { s.hostKey }.getOrNull()
                pendingKey = hk
                pendingHostKeyChanged = true
                val oldFp = readSavedFingerprint()
                val offered = formatHostKey(profile.host, profile.port, hk)
                pendingHostKey = buildString {
                    append("Server key for ${profile.host}:${profile.port} CHANGED.")
                    if (oldFp != null) append("\nSaved:   $oldFp")
                    if (offered != null) append("\nOffered: $offered")
                    if (oldFp == null && offered == null) append("\n$msg")
                }
                _state.value = SshState.ERROR
                _status.value = "Server host key changed. Review before accepting."
                throw ChangedHostKeyException(pendingHostKey!!)
            }
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
        ch.setPtyType("xterm-256color", ptyCols, ptyRows, 0, 0)
        // canonical stdin wiring: our pipe feeds the remote shell (getOutputStream
        // is unreliable across JSch versions for interactive shells)
        val pipeIn = java.io.PipedInputStream(64 * 1024)
        shellInput = java.io.PipedOutputStream(pipeIn)
        ch.setInputStream(pipeIn)
        ch.connect(10_000)
        session = s
        channel = ch
        // Channel opened at the current pty size; later setPtySize calls only
        // send what actually changed. Reconnect reuses this object, so reset.
        networkClosed = false
        lastSentCols = ptyCols
        lastSentRows = ptyRows
        _state.value = SshState.CONNECTED
        _status.value = "Connected"
        startForwards()
        val input = ch.inputStream
        readerJob = CoroutineScope(Dispatchers.IO).launch {
            val buf = ByteArray(8192)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        buffer.feed(buf, 0, n)
                        // terminal replies (DSR/CPR) must go back to the server,
                        // otherwise full-screen apps stall waiting for them
                        for (reply in buffer.takePendingOutput()) {
                            writeStdin(reply)
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                _state.value = SshState.DISCONNECTED
                _status.value = "Disconnected"
            }
        }
    }

    private fun hostPart(): String =
        if (profile.port == 22) profile.host else "[${profile.host}]:${profile.port}"

    /** Fingerprint of the currently saved pin for this host, if any. */
    private fun readSavedFingerprint(): String? = runCatching {
        val f = knownHostsFile?.takeIf { it.exists() } ?: return null
        val prefix = hostPart() + " "
        f.readLines()
            .firstOrNull { it.startsWith(prefix) }
            ?.split(" ")
            ?.getOrNull(2)
            ?.let { sha256Fingerprint(it) }
    }.getOrNull()

    /**
     * Saves the accepted key to known_hosts (OpenSSH format), replacing any
     * previous pin for this host (changed-key accept must not leave the stale
     * line behind — JSch would keep rejecting). Written via temp-file + rename
     * so a crash can never leave a truncated known_hosts behind.
     */
    private fun savePendingKey(): Boolean {
        val hk = pendingKey ?: return false
        val f = knownHostsFile ?: return false
        return runCatching {
            val prefix = hostPart() + " "
            f.parentFile?.mkdirs()
            val kept = if (f.exists()) {
                f.readLines().filterNot { it.startsWith(prefix) }
            } else emptyList()
            // HostKey.key is already base64 in JSch - store verbatim (OpenSSH line format)
            val lines = replacePin(kept, prefix, "${hostPart()} ${hk.type} ${hk.key}")
            val tmp = java.io.File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(lines.joinToString("\n") + "\n")
            if (!tmp.renameTo(f)) {
                // rename across volumes can fail: fall back to direct write
                f.writeText(lines.joinToString("\n") + "\n")
            }
            tmp.takeIf { it.exists() }?.delete()
            pendingKey = null
            pendingHostKeyChanged = false
            true
        }.getOrDefault(false)
    }

    override fun send(text: String) {
        writeStdin(text.toByteArray())
    }

    override fun sendKey(key: String) = send(key)

    /**
     * Resize the remote pty (SIGWINCH; TUIs redraw). Safe to call before
     * connect (sizes the next channel) or while connected (live resize).
     */
    override fun setPtySize(cols: Int, rows: Int) {
        ptyCols = cols.coerceIn(20, 300)
        ptyRows = rows.coerceIn(10, 200)
        if (networkClosed) return
        try {
            pendingResize?.cancel(false)
            // Isolated change (e.g. keyboard toggled after idle): send NOW so
            // the terminal reacts instantly. Bursts (animation frames) fall
            // through to one trailing settled request. Both run on the single
            // network worker — channel requests must never run on UI threads.
            if (System.currentTimeMillis() - lastResizeSentAt >= 700) {
                networkExecutor.execute { sendPtySizeNow() }
            }
            pendingResize = networkExecutor.schedule({
                sendPtySizeNow()
            }, 250, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        }
    }

    private fun sendPtySizeNow() {
        try {
            val ch = channel
            if (!networkClosed && ch != null && ch.isConnected &&
                (ptyCols != lastSentCols || ptyRows != lastSentRows)
            ) {
                ch.setPtySize(ptyCols, ptyRows, 0, 0)
                lastSentCols = ptyCols
                lastSentRows = ptyRows
                lastResizeSentAt = System.currentTimeMillis()
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Starts the profile's enabled port forwards (non-fatal: one bad forward
     * never kills the session; failures are reported in [forwardStatus]).
     */
    private fun startForwards() {
        val s = session ?: return
        if (!forwardsOn.value) {
            _forwardStatus.value = ""
            return
        }
        val enabled = profile.forwards.filter { it.enabled }
        if (enabled.isEmpty()) {
            _forwardStatus.value = ""
            return
        }
        var ok = 0
        val errs = mutableListOf<String>()
        activeForwards.clear()
        for (f in enabled) {
            val sane = at.websters.tabbyandroid.data.model.sanitizeForward(f)
            try {
                if (sane.kind == "remote") {
                    s.setPortForwardingR(sane.remotePort, sane.remoteHost, sane.localPort)
                } else {
                    s.setPortForwardingL(sane.localPort, sane.remoteHost, sane.remotePort)
                }
                activeForwards.add(sane)
                ok++
            } catch (e: Exception) {
                errs += "${at.websters.tabbyandroid.data.model.describeForward(sane)}: " +
                    (e.message?.take(120) ?: "failed")
            }
        }
        _forwardStatus.value = when {
            errs.isEmpty() -> "$ok forward${if (ok == 1) "" else "s"} active"
            ok == 0 -> "Forward failed: ${errs.first()}"
            else -> "$ok active, ${errs.size} failed: ${errs.first()}"
        }
    }

    private fun stopForwards() {
        val s = session
        for (f in activeForwards.toList()) {
            try {
                if (s != null) {
                    if (f.kind == "remote") s.delPortForwardingR(f.remotePort)
                    else s.delPortForwardingL(f.localPort)
                }
            } catch (_: Exception) {
            }
        }
        activeForwards.clear()
        if (_state.value != SshState.CONNECTED) _forwardStatus.value = ""
    }

    override fun setForwardsActive(active: Boolean) {
        _forwardsOn.value = active
        if (_state.value != SshState.CONNECTED) return
        if (active) startForwards()
        else {
            stopForwards()
            _forwardStatus.value = "Forwards paused"
        }
    }

    private val activeForwards = mutableListOf<at.websters.tabbyandroid.data.model.PortForward>()

    override fun close() {
        networkClosed = true
        try { pendingResize?.cancel(false) } catch (_: Exception) {}
        pendingResize = null
        try { readerJob?.cancel() } catch (_: Exception) {}
        try { shellInput?.close() } catch (_: Exception) {}
        try { stopForwards() } catch (_: Exception) {}
        try { channel?.disconnect() } catch (_: Exception) {}
        try { session?.disconnect() } catch (_: Exception) {}
        // NOTE: networkExecutor stays alive — the same connection object can
        // reconnect (Reconnect button), which resets networkClosed above.
        _state.value = SshState.DISCONNECTED
    }
}

open class UnknownHostKeyException(message: String) : Exception(message)

/**
 * The server offers a DIFFERENT key than the saved pin (possible MITM —
 * accept only when the server was knowingly reinstalled). Handled like
 * [UnknownHostKeyException] (same accept-and-retry path) but shown with an
 * explicit warning in the review dialog.
 */
class ChangedHostKeyException(message: String) : UnknownHostKeyException(message)

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

/**
 * Returns known_hosts lines with any stale pin for [prefix] replaced by
 * [entry] (pure, unit-tested). Keeping this logic out of the file write
 * means the on-disk update is a single dumb dump of the result.
 */
internal fun replacePin(kept: List<String>, prefix: String, entry: String): List<String> =
    kept.filterNot { it.startsWith(prefix) } + entry

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
                "Possible attack: review in the popup and accept only if you " +
                "reinstalled the server on purpose. " +
                "To start over, use Forget saved host keys (Settings → Privacy)."
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
