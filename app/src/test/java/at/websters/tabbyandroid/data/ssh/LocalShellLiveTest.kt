package at.websters.tabbyandroid.data.ssh

import at.websters.tabbyandroid.data.model.SshProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/** Live demo-shell integration (needs a POSIX sh; skipped without one). */
class LocalShellLiveTest {
    private fun shPath(): String? =
        listOf("/system/bin/sh", "/usr/bin/sh", "/bin/sh")
            .firstOrNull { java.io.File(it).exists() }

    private fun conn(): LocalShellConnection {
        val sh = shPath()
        Assume.assumeTrue(sh != null)
        val p = SshProfile(id = "demo:t", name = "t", host = "device", port = 0, username = "s")
        return LocalShellConnection(p, shellPath = sh!!)
    }

    private fun awaitText(c: LocalShellConnection, needle: String, timeoutMs: Long = 8000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (c.buffer.visibleText().contains(needle)) return true
            Thread.sleep(100)
        }
        return false
    }

    @Test fun wholeLinePlusEnterExecutes() {
        val c = conn()
        try {
            runBlocking { c.connect("") }
            // exactly what macro-run does: full line, then CR, back-to-back
            c.send("echo ITWORKS")
            c.send("\r")
            assertTrue("no output:\n" + c.buffer.visibleText(), awaitText(c, "ITWORKS"))
        } finally {
            c.close()
        }
    }

    @Test fun concurrentSendsDoNotLose() {
        val c = conn()
        try {
            runBlocking { c.connect("") }
            val ts = (1..10).map { i ->
                Thread {
                    c.send("echo CONC$i")
                    c.send("\r")
                }.also { it.start() }
            }
            ts.forEach { it.join(10000) }
            // every command must execute (no stuck lines, no garble):
            // poll until all markers show or timeout
            val end = System.currentTimeMillis() + 15000
            var missing = (1..10).map { "echo CONC$it" }
            while (System.currentTimeMillis() < end && missing.isNotEmpty()) {
                val t = c.buffer.visibleText()
                missing = missing.filterNot { t.contains(it) }
                if (missing.isNotEmpty()) Thread.sleep(150)
            }
            assertTrue("lost markers: $missing", missing.isEmpty())
        } finally {
            c.close()
        }
    }
}
