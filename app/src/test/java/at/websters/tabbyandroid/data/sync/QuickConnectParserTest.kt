package at.websters.tabbyandroid.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickConnectParserTest {
    @Test fun userHostPort() {
        val p = QuickConnectParser.parse("bob@example.com:2222")
        assertEquals("bob", p.username)
        assertEquals("example.com", p.host)
        assertEquals(2222, p.port)
    }

    @Test fun hostOnlyDefaultsRoot22() {
        val p = QuickConnectParser.parse("example.com")
        assertEquals("root", p.username)
        assertEquals(22, p.port)
    }

    @Test fun ipv6() {
        val p = QuickConnectParser.parse("[::1]:2222")
        assertEquals("::1", p.host)
        assertEquals(2222, p.port)
    }
}
