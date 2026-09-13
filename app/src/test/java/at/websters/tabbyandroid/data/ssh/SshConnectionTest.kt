package at.websters.tabbyandroid.data.ssh

import at.websters.tabbyandroid.data.model.SshProfile
import org.junit.Assert.*
import org.junit.Test

class SshConnectionTest {
    private fun conn() = SshConnection(
        SshProfile(id = "t", name = "t", host = "example.com", port = 2222, username = "u")
    )

    @Test fun refusedIsFriendly() {
        val msg = conn().friendlyError(
            java.net.ConnectException("failed to connect (port 2222): ECONNREFUSED")
        )
        assertTrue(msg.contains("Connection refused"))
        assertTrue(msg.contains("example.com:2222"))
    }

    @Test fun authFailIsFriendly() {
        val msg = conn().friendlyError(Exception("Auth fail"))
        assertTrue(msg.contains("Authentication failed"))
    }

    @Test fun timeoutIsFriendly() {
        val msg = conn().friendlyError(java.net.SocketTimeoutException("read timed out"))
        assertTrue(msg.contains("Timed out"))
    }

    @Test fun unknownHostIsFriendly() {
        val msg = conn().friendlyError(java.net.UnknownHostException("nope.example"))
        assertTrue(msg.contains("Unknown host"))
    }

    @Test fun algoFailKeepsServerOffer() {
        val msg = conn().friendlyError(
            Exception("Algorithm negotiation fail: serverProposal=\"ssh-ed25519\"")
        )
        assertTrue(msg.contains("No shared SSH algorithms"))
        assertTrue(msg.contains("ssh-ed25519"))
    }

    @Test fun changedKeyIsHardBlock() {
        val msg = conn().friendlyError(Exception("HostKey has been changed: attacker.example"))
        assertTrue(msg.contains("CHANGED"))
        assertTrue(msg.contains("Forget saved host keys"))
    }

    @Test fun replacePinSwapsStaleEntry() {
        val out = replacePin(
            listOf("a.example ssh-rsa AAA", "b.example ssh-rsa BBB"),
            "b.example ",
            "b.example ssh-ed25519 CCC",
        )
        assertEquals(listOf("a.example ssh-rsa AAA", "b.example ssh-ed25519 CCC"), out)
    }

    @Test fun sha256FingerprintVector() {
        // base64("abc") = "YWJj"; must match `echo -n abc | sha256sum` rendering
        assertEquals(
            "SHA256:ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0",
            sha256Fingerprint("YWJj"),
        )
    }
}
