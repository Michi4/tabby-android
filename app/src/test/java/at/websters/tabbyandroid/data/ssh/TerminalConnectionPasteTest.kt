package at.websters.tabbyandroid.data.ssh

import at.websters.tabbyandroid.data.model.SshProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalConnectionPasteTest {
    @Test fun pasteIsRawWhenBracketedPasteModeIsOff() {
        val c = RecordingConnection()
        c.sendPaste("hello\nworld")
        assertEquals(listOf("hello\nworld"), c.sent)
    }

    @Test fun pasteIsBracketedWhenServerEnabledMode2004() {
        val c = RecordingConnection()
        c.buffer.feed("\u001B[?2004h".toByteArray())
        c.sendPaste("hello\nworld")
        assertEquals(listOf("\u001B[200~hello\nworld\u001B[201~"), c.sent)
    }

    private class RecordingConnection : TerminalConnection {
        val sent = mutableListOf<String>()
        override val profile = SshProfile(id = "recording", name = "recording", host = "example.invalid")
        override val buffer = TerminalBuffer(cols = 80, rows = 24)
        override val state: StateFlow<SshState> = MutableStateFlow(SshState.DISCONNECTED)
        override val status: StateFlow<String> = MutableStateFlow("")
        override val pendingHostKey: String? = null
        override val pendingHostKeyChanged = false
        override val forwardStatus: StateFlow<String> = MutableStateFlow("")
        override val forwardsOn: StateFlow<Boolean> = MutableStateFlow(false)

        override suspend fun connect(
            password: String,
            privateKeyPem: String?,
            privateKeyPassphrase: String?,
            acceptHostKey: Boolean,
        ): Result<Unit> = Result.success(Unit)

        override fun send(text: String) { sent += text }
        override fun setPtySize(cols: Int, rows: Int) = Unit
        override fun setForwardsActive(active: Boolean) = Unit
        override fun close() = Unit
    }
}
