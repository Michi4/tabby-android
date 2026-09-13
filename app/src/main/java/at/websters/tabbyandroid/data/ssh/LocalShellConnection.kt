package at.websters.tabbyandroid.data.ssh

import at.websters.tabbyandroid.data.model.SshProfile
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Profile id prefix for on-device demo tabs (never synced, never uploaded). */
const val DEMO_SHELL_PREFIX = "demo:"

/** The demo tab's fake profile (shown as "Demo shell · on-device"). */
fun demoShellProfile(): at.websters.tabbyandroid.data.model.SshProfile =
    at.websters.tabbyandroid.data.model.SshProfile(
        id = DEMO_SHELL_PREFIX + "local-shell",
        name = "Demo shell",
        host = "device",
        port = 0,
        username = "shell",
    )

/** Result of feeding user input through the demo line discipline. */
data class DemoInputResult(
    /** Bytes to write to the shell stdin. */
    val procBytes: ByteArray,
    /** Bytes to echo back to the screen. */
    val echo: ByteArray,
    /** True when the shell should see EOF (Ctrl+D on an empty line). */
    val eof: Boolean = false,
)

/**
 * Tiny cooked-mode line discipline for the demo shell (pipes have no tty, so
 * the kernel does no echo/editing for us). Pure logic, fully unit-tested:
 * accumulates the line, handles backspace locally, submits on Enter, Ctrl+C
 * clears, Ctrl+D on an empty line ends the session.
 */
class DemoLineDiscipline {
    private val line = StringBuilder()

    fun reset() = line.clear()

    val currentLine: String get() = line.toString()

    fun input(text: String): DemoInputResult {
        val proc = ByteArrayOutputStream()
        val echo = ByteArrayOutputStream()
        var eof = false
        for (ch in text) {
            when {
                ch == '\r' || ch == '\n' -> {
                    proc.write((line.toString() + "\n").toByteArray(Charsets.UTF_8))
                    echo.write("\r\n$ ".toByteArray(Charsets.UTF_8))
                    line.clear()
                }
                ch.code == 0x7F || ch.code == 0x08 -> {
                    if (line.isNotEmpty()) {
                        line.deleteCharAt(line.length - 1)
                        echo.write("\b \b".toByteArray(Charsets.UTF_8))
                    }
                }
                ch.code == 0x03 -> {
                    line.clear()
                    echo.write("^C\r\n$ ".toByteArray(Charsets.UTF_8))
                }
                ch.code == 0x04 -> {
                    if (line.isEmpty()) eof = true
                }
                ch.code < 0x20 -> Unit // swallow other C0 controls
                else -> {
                    line.append(ch)
                    echo.write(ch.toString().toByteArray(Charsets.UTF_8))
                }
            }
        }
        return DemoInputResult(proc.toByteArray(), echo.toByteArray(), eof)
    }
}

/**
 * On-device demo shell (`/system/bin/sh` via pipes) so the terminal —
 * key rows, modifiers, fullscreen, fonts — can be tried with no server and
 * no sync. Basic commands (`ls`, `echo`, pipes) stream live; full-screen
 * TUIs need a real SSH server (no pty on-device).
 */
class LocalShellConnection(
    override val profile: SshProfile,
    override val buffer: TerminalBuffer = TerminalBuffer(cols = 80, rows = 24),
) : TerminalConnection {
    private val _state = MutableStateFlow(SshState.DISCONNECTED)
    override val state: StateFlow<SshState> = _state
    private val _status = MutableStateFlow("Not started")
    override val status: StateFlow<String> = _status

    override val pendingHostKey: String? = null
    override val pendingHostKeyChanged: Boolean = false

    private var proc: Process? = null
    private var stdin: OutputStream? = null
    private var readerJob: Job? = null
    private val discipline = DemoLineDiscipline()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override suspend fun connect(
        password: String,
        privateKeyPem: String?,
        privateKeyPassphrase: String?,
        acceptHostKey: Boolean,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (proc?.isAlive == true) {
            _state.value = SshState.CONNECTED
            return@withContext Result.success(Unit)
        }
        try {
            start()
            Result.success(Unit)
        } catch (e: Exception) {
            _state.value = SshState.ERROR
            _status.value = (e.message?.take(200) ?: "Demo shell failed to start")
            Result.failure(e)
        }
    }

    private fun start() {
        _state.value = SshState.CONNECTING
        _status.value = "Starting demo shell…"
        val pb = ProcessBuilder("/system/bin/sh").redirectErrorStream(true)
        pb.environment()["TERM"] = "xterm-256color"
        val p = pb.start()
        proc = p
        stdin = p.outputStream
        discipline.reset()
        buffer.feed(
            ("Local demo shell — on-device, no server needed.\r\n" +
                "Try: ls, echo hi, id. Full-screen apps need real SSH.\r\n$ ")
                .toByteArray(Charsets.UTF_8),
        )
        _state.value = SshState.CONNECTED
        _status.value = "On-device demo shell"
        readerJob = scope.launch {
            val buf = ByteArray(8192)
            try {
                while (true) {
                    val n = p.inputStream.read(buf)
                    if (n < 0) break
                    if (n > 0) buffer.feed(buf, 0, n)
                }
            } catch (_: Exception) {
            } finally {
                proc = null
                _state.value = SshState.DISCONNECTED
                _status.value = "Shell exited — Reconnect to restart"
            }
        }
    }

    override fun send(text: String) {
        val p = proc ?: return
        val o = stdin ?: return
        if (p.isAlive != true) return
        scope.launch {
            try {
                val r = discipline.input(text)
                if (r.echo.isNotEmpty()) buffer.feed(r.echo, 0, r.echo.size)
                if (r.procBytes.isNotEmpty()) {
                    o.write(r.procBytes)
                    o.flush()
                }
                if (r.eof) runCatching { o.close() }
            } catch (_: Exception) {
            }
        }
    }

    override fun setPtySize(cols: Int, rows: Int) = Unit

    override fun close() {
        try { readerJob?.cancel() } catch (_: Exception) {}
        try { stdin?.close() } catch (_: Exception) {}
        try { proc?.destroy() } catch (_: Exception) {}
        proc = null
        stdin = null
        _state.value = SshState.DISCONNECTED
    }
}
