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
                conn.send("printf 'LIVEPROBE_%s\\n' 987\n")
                // CR variant: does a lone carriage return execute?
                conn.send("printf 'CRPROBE_%s\\n' 654")
                conn.send("\r")
                withTimeout(15_000) {
                    while (!conn.buffer.visibleText().contains("LIVEPROBE_987")) {
                        delay(200)
                    }
                }
                val text = conn.buffer.visibleText()
                assertTrue("no echo/output, got:\n$text", text.contains("LIVEPROBE_987"))
                // The typed command contains only printf '%s', so the marker appearing
                // once below the prompt proves that the lone CR executed it.
                withTimeout(10_000) {
                    while (!conn.buffer.visibleText().contains("CRPROBE_654")) {
                        delay(200)
                    }
                }
                println("CR-EXECUTES: lone carriage return runs the command")
            } finally {
                conn.close()
            }
        } finally {
            knownHosts.delete()
        }
    }

    @Test fun terminalLineEditingResizeAndUtf8(): Unit = runBlocking {
        val cfg = env()
        Assume.assumeTrue("no live SSH rig (set TABBY_TEST_SSH_* env)", cfg != null)
        cfg!!
        val profile = SshProfile(
            id = "liveedit", name = "liveedit", host = cfg["host"]!!,
            port = cfg["port"]!!.toInt(), username = cfg["user"]!!,
        )
        val knownHosts = kotlin.io.path.createTempFile("known_hosts").toFile()
        try {
            knownHosts.writeText("")
            val conn = SshConnection(profile, TerminalBuffer(cols = 80, rows = 24), knownHosts)
            try {
                val r = conn.connect(cfg["password"]!!, acceptHostKey = true)
                assertTrue("connect failed: ${r.exceptionOrNull()}", r.isSuccess)
                assertEquals(SshState.CONNECTED, conn.state.value)

                conn.setPtySize(71, 23)
                conn.send("stty size\r")
                withTimeout(15_000) {
                    while (!conn.buffer.visibleText().contains("23 71")) delay(200)
                }

                conn.send("printf 'BS_%s\\n' A")
                conn.send("\u007f")
                conn.send("B\r")
                withTimeout(15_000) {
                    while (!conn.buffer.visibleText().contains("BS_B")) delay(200)
                }

                conn.send("read -r x; printf 'UTF_%s\\n' \"\$x\"\r")
                withTimeout(15_000) {
                    while (!conn.buffer.visibleText().contains("printf")) delay(200)
                }
                conn.send("\u00c4\u00d6\u00dc\r")
                withTimeout(15_000) {
                    while (!conn.buffer.visibleText().contains("UTF_\u00c4\u00d6\u00dc")) delay(200)
                }
            } finally {
                conn.close()
            }
        } finally {
            knownHosts.delete()
        }
    }

    @Test fun ctrlCIsForwardedToServer(): Unit = runBlocking {
        val cfg = env()
        Assume.assumeTrue("no live SSH rig (set TABBY_TEST_SSH_* env)", cfg != null)
        cfg!!
        val profile = SshProfile(
            id = "livectrlc", name = "livectrlc", host = cfg["host"]!!,
            port = cfg["port"]!!.toInt(), username = cfg["user"]!!,
        )
        val knownHosts = kotlin.io.path.createTempFile("known_hosts").toFile()
        try {
            knownHosts.writeText("")
            val conn = SshConnection(profile, TerminalBuffer(cols = 80, rows = 24), knownHosts)
            try {
                val r = conn.connect(cfg["password"]!!, acceptHostKey = true)
                assertTrue("connect failed: ${r.exceptionOrNull()}", r.isSuccess)
                conn.send("sleep 3\r")
                withTimeout(15_000) {
                    while (!conn.buffer.visibleText().contains("sleep 3")) delay(200)
                }
                conn.send("\u0003")
                withTimeout(15_000) {
                    while (!conn.buffer.visibleText().contains("^C")) delay(200)
                }
            } finally {
                conn.close()
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
