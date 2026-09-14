package at.websters.tabbyandroid.data.model

import org.junit.Assert.*
import org.junit.Test

class PortForwardTest {
    @Test fun sanitizeClamps() {
        val f = sanitizeForward(
            PortForward(kind = "weird", localPort = 0, remoteHost = "  ", remotePort = 99999)
        )
        assertEquals("local", f.kind)
        assertEquals(1, f.localPort)
        assertEquals(65535, f.remotePort)
        assertEquals("localhost", f.remoteHost)
        assertEquals("remote", sanitizeForward(PortForward(kind = "remote")).kind)
    }

    @Test fun describeFormats() {
        assertEquals(
            "L :8080 → example.com:80",
            describeForward(PortForward(kind = "local", localPort = 8080, remoteHost = "example.com", remotePort = 80)),
        )
        assertEquals(
            "R :8080 → localhost:3000",
            describeForward(PortForward(kind = "remote", localPort = 3000, remoteHost = "localhost", remotePort = 8080)),
        )
    }

    @Test fun profileDefaultsToNoForwards() {
        val p = SshProfile(id = "x", name = "x", host = "h")
        assertTrue(p.forwards.isEmpty())
        assertEquals(p, p.copy())
    }
}
