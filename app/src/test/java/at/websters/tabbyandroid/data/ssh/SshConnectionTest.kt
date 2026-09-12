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
}
