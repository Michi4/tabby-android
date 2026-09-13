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
            // CR variant: does a lone carriage return execute?
            conn.send("echo CRPROBE654")
            conn.send("\r")
            withTimeout(15_000) {
                while (!conn.buffer.visibleText().contains("LIVEPROBE987")) {
                    delay(200)
                }
            }
            val text = conn.buffer.visibleText()
            assertTrue("no echo/output, got:\n$text", text.contains("LIVEPROBE987"))
            // CR check: typed line echoes once; a second occurrence means it EXECUTED
            withTimeout(10_000) {
                while (conn.buffer.visibleText().split("CRPROBE654").size < 3) {
                    delay(200)
                }
            }
            println("CR-EXECUTES: lone carriage return runs the command")
        } finally {
            conn.close()
            knownHosts.delete()
        }
        } finally {
            knownHosts.delete()
        }
    }

    /**
     * KEY-auth variant: device-style RSA key (no passphrase), empty password.
     * Proves addIdentity path + stdin pipe execute a command (marker file).
     * Needs TABBY_TEST_SSH_KEY (private PEM path, authorized on the rig).
     */
    @Test fun keySessionExecMarker(): Unit = runBlocking {
        val cfg = env()
        Assume.assumeTrue("no live SSH rig (set TABBY_TEST_SSH_* env)", cfg != null)
        cfg!!
        val keyPath = System.getenv("TABBY_TEST_SSH_KEY")
        Assume.assumeTrue("no test key (set TABBY_TEST_SSH_KEY)", !keyPath.isNullOrBlank())
        val profile = SshProfile(
            id = "livekey", name = "livekey", host = cfg["host"]!!,
            port = cfg["port"]!!.toInt(), username = cfg["user"]!!,
        )
        val knownHosts = kotlin.io.path.createTempFile("known_hosts").toFile()
        try {
            knownHosts.writeText("")
            val conn = SshConnection(profile, knownHostsFile = knownHosts)
            try {
                val pem = java.io.File(keyPath!!).readText()
                val r = conn.connect("", privateKeyPem = pem, privateKeyPassphrase = "", acceptHostKey = true)
                assertTrue("key connect failed: ${r.exceptionOrNull()}", r.isSuccess)
                assertEquals(SshState.CONNECTED, conn.state.value)
                val marker = "/tmp/jvmkey-ok"
                java.io.File(marker).delete()
                conn.send("touch $marker\r")
                withTimeout(15_000) {
                    while (!java.io.File(marker).exists()) {
                        delay(200)
                    }
                }
                println("KEY-EXECUTES: key-auth session ran remote command")
            } finally {
                conn.close()
            }
        } finally {
            knownHosts.delete()
        }
    }
}
