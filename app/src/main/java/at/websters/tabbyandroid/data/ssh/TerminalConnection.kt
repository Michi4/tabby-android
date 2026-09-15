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

    /** Human port-forward summary, "" when none (SSH only). */
    val forwardStatus: StateFlow<String>

    /** Live-forward master switch (SSH only; per-forward `enabled` applies on start). */
    val forwardsOn: StateFlow<Boolean>

    suspend fun connect(
        password: String,
        privateKeyPem: String? = null,
        privateKeyPassphrase: String? = null,
        acceptHostKey: Boolean = false,
    ): Result<Unit>

    fun send(text: String)

    fun sendKey(key: String) = send(key)

    /** Paste text, honoring DEC private mode 2004 when the server enabled it. */
    fun sendPaste(text: String) {
        if (text.isEmpty()) return
        if (buffer.bracketedPaste) {
            send("\u001B[200~$text\u001B[201~")
        } else {
            send(text)
        }
    }

    /** Resize the remote pty (best-effort; no-op for the demo shell). */
    fun setPtySize(cols: Int, rows: Int)

    /** Stops (false) or (re)starts (true) the profile's enabled forwards. */
    fun setForwardsActive(active: Boolean)
}
