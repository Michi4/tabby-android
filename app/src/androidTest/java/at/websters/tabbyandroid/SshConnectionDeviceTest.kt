package at.websters.tabbyandroid

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import at.websters.tabbyandroid.data.model.SshProfile
import at.websters.tabbyandroid.data.ssh.SshConnection
import at.websters.tabbyandroid.data.ssh.SshState
import at.websters.tabbyandroid.data.ssh.TerminalBuffer
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device-side SSH checks against the local paramiko rig. Run the rig first:
 *
 *   TABBY_SSH_HOST=0.0.0.0 ... /tmp/ssh-venv/bin/python /tmp/tabby_ssh_server.py
 *
 * Android emulators reach the host loopback through 10.0.2.2.
 */
class SshConnectionDeviceTest {

    @Test
    fun echoRoundTrip() = withConnection { conn ->
        conn.buffer.reset()
        conn.send("printf 'DEVICE_ECHO_%s\\n' OK\r")
        waitFor(conn, "DEVICE_ECHO_OK")
    }

    @Test
    fun rapidSendsStayOrdered() = withConnection { conn ->
        conn.buffer.reset()
        "printf 'Rapid%s\\n' Device\r".forEach { conn.send(it.toString()) }
        waitFor(conn, "RapidDevice")
    }

    @Test
    fun backspaceDeletesTypedCharacter() = withConnection { conn ->
        conn.buffer.reset()
        conn.send("printf 'FILL%s\\n' M")
        conn.send("\u007f")
        conn.send("X\r")
        waitFor(conn, "FILLX")
    }

    @Test
    fun ptyResizeReachesRemoteShell() = withConnection { conn ->
        conn.setPtySize(73, 27)
        conn.buffer.reset()
        conn.send("stty size\r")
        waitFor(conn, "27 73")
    }

    @Test
    fun unicodeUtf8InputAndOutput() = withConnection { conn ->
        conn.buffer.reset()
        conn.send("read -r x; printf 'GOT_%s\\n' \"\$x\"\r")
        waitFor(conn, "printf")
        conn.send("\u00c4\u00d6\u00dc\r")
        waitForOccurrences(conn, "\u00c4\u00d6\u00dc", 2)
    }

    @Test
    fun ctrlCIsForwardedToServer() = withConnection { conn ->
        conn.buffer.reset()
        conn.send("sleep 3\r")
        waitFor(conn, "sleep 3")
        conn.send("\u0003")
        waitFor(conn, "^C")
    }

    private fun withConnection(
        cols: Int = 80,
        rows: Int = 24,
        body: (SshConnection) -> Unit,
    ) {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("ssh.host") ?: "10.0.2.2"
        val port = args.getString("ssh.port")?.toIntOrNull() ?: 2222
        val user = args.getString("ssh.user") ?: "tabby"
        val password = args.getString("ssh.password") ?: "tabbytest"
        val known = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "known_hosts_device_test").apply { delete() }
        val profile = SshProfile(
            id = "device-test",
            name = "Device test",
            host = host,
            port = port,
            username = user,
            authType = "password",
            keepaliveIntervalSec = 0,
        )
        val conn = SshConnection(profile, TerminalBuffer(cols = cols, rows = rows), known)
        try {
            conn.setPtySize(cols, rows)
            val result = runBlocking {
                conn.connect(password = password, acceptHostKey = true)
            }
            assertTrue("connect failed: ${result.exceptionOrNull()?.message}", result.isSuccess)
            assertEquals(SshState.CONNECTED, conn.state.value)
            body(conn)
        } finally {
            runCatching { conn.close() }
        }
    }

    private fun waitFor(conn: SshConnection, needle: String, timeoutMs: Long = 20_000): String {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var last = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            last = conn.buffer.visibleText()
            if (last.contains(needle)) return last
            Thread.sleep(100)
        }
        assertTrue("timeout waiting for '$needle'.\nvisible:\n$last", false)
        return last
    }

    private fun waitForOccurrences(
        conn: SshConnection,
        needle: String,
        minimum: Int,
        timeoutMs: Long = 20_000,
    ): String {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var last = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            last = conn.buffer.visibleText()
            if (occurrences(last, needle) >= minimum) return last
            Thread.sleep(100)
        }
        assertTrue("timeout waiting for $minimum occurrences of '$needle'.\nvisible:\n$last", false)
        return last
    }

    private fun occurrences(text: String, needle: String): Int {
        var count = 0
        var index = text.indexOf(needle)
        while (index >= 0) {
            count++
            index = text.indexOf(needle, index + needle.length)
        }
        return count
    }
}
