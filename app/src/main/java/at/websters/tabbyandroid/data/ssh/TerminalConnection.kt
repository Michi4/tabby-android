package at.websters.tabbyandroid.data.ssh

import at.websters.tabbyandroid.data.model.SshProfile
import java.io.Closeable
import kotlinx.coroutines.flow.StateFlow

/**
 * Common terminal backend: a live SSH session ([SshConnection]) or the
 * on-device demo shell ([LocalShellConnection], no server needed).
 * The UI only talks to this interface, so tabs, key rows, fullscreen and
 * reconnect logic work identically for both.
 */
interface TerminalConnection : Closeable {
    val profile: SshProfile
    val buffer: TerminalBuffer
    val state: StateFlow<SshState>
    val status: StateFlow<String>

    /** TOFU host-key prompt (SSH only; always null for the demo shell). */
    val pendingHostKey: String?
    val pendingHostKeyChanged: Boolean

    suspend fun connect(
        password: String,
        privateKeyPem: String? = null,
        privateKeyPassphrase: String? = null,
        acceptHostKey: Boolean = false,
    ): Result<Unit>

    fun send(text: String)

    fun sendKey(key: String) = send(key)

    /** Resize the remote pty (best-effort; no-op for the demo shell). */
    fun setPtySize(cols: Int, rows: Int)
}
