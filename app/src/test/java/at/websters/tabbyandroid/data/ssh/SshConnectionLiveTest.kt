package at.websters.tabbyandroid.data.ssh

import at.websters.tabbyandroid.data.model.SshProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test

/**
 * LIVE test against a real OpenSSH server. Only runs when the test rig env is
 * present (local throwaway sshd, never touched infra):
 * TABBY_TEST_SSH_HOST / TABBY_TEST_SSH_PORT / TABBY_TEST_SSH_USER / TABBY_TEST_SSH_PASSWORD
 *
 * Exercises the exact production path: SshConnection.connect + send + buffer.
 */
class SshConnectionLiveTest {
    private fun env(): Map<String, String>? {
        val e = System.getenv()
        val host = e["TABBY_TEST_SSH_HOST"] ?: return null
        return mapOf(
            "host" to host,
            "port" to (e["TABBY_TEST_SSH_PORT"] ?: "22"),
            "user" to (e["TABBY_TEST_SSH_USER"] ?: "root"),
            "password" to (e["TABBY_TEST_SSH_PASSWORD"] ?: ""),
        )
    }

    @Test fun passwordSessionEchoAndExec(): Unit = runBlocking {
        val cfg = env()
        Assume.assumeTrue("no live SSH rig (set TABBY_TEST_SSH_* env)", cfg != null)
        cfg!!
        val profile = SshProfile(
            id = "live", name = "live", host = cfg["host"]!!,
            port = cfg["port"]!!.toInt(), username = cfg["user"]!!,
        )
        val knownHosts = kotlin.io.path.createTempFile("known_hosts").toFile()
        try {
            knownHosts.writeText("")
            val conn = SshConnection(profile, knownHostsFile = knownHosts)
            try {
            // rig key is verified out-of-band (matches ssh-keygen -l); accept it
            val r = conn.connect(cfg["password"]!!, acceptHostKey = true)
            assertTrue("connect failed: ${r.exceptionOrNull()}", r.isSuccess)
            assertEquals(SshState.CONNECTED, conn.state.value)
            conn.send("echo LIVEPROBE987\n")
            withTimeout(15_000) {
                while (!conn.buffer.visibleText().contains("LIVEPROBE987")) {
                    delay(200)
                }
            }
            val text = conn.buffer.visibleText()
            assertTrue("no echo/output, got:\n$text", text.contains("LIVEPROBE987"))
        } finally {
            conn.close()
            knownHosts.delete()
        }
        } finally {
            knownHosts.delete()
        }
    }
}
