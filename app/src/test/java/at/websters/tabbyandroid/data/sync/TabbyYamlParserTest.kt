package at.websters.tabbyandroid.data.sync

import org.junit.Assert.*
import org.junit.Test

class TabbyYamlParserTest {
    @Test fun emptyConfigYieldsNoProfiles() {
        assertTrue(TabbyYamlParser.parseSshProfiles("{}", "o").isEmpty())
        assertTrue(TabbyYamlParser.parseSshProfiles("", "o").isEmpty())
        assertTrue(TabbyYamlParser.parseSshProfiles("version: 7", "o").isEmpty())
    }

    @Test fun parsesSshProfiles() {
        val yaml = """
            version: 7
            profiles:
              - type: ssh
                name: relic02
                id: ssh:custom:relic02:abc
                color: '#ffd65b'
                group: g1
                options:
                  host: 192.168.66.102
                  port: 2222
                  user: acolyte
                  auth: publicKey
              - type: local
                name: local - bash
                options:
                  command: /bin/bash
              - type: ssh
                name: bad
                options:
                  host: ''
        """.trimIndent()
        val out = TabbyYamlParser.parseSshProfiles(yaml, "tabby:acc:1")
        assertEquals(1, out.size)
        val p = out[0]
        assertEquals("relic02", p.name)
        assertEquals("192.168.66.102", p.host)
        assertEquals(2222, p.port)
        assertEquals("acolyte", p.username)
        assertEquals("publicKey", p.authType)
        assertEquals("tabby:acc:1", p.origin)
    }

    @Test fun defaultsPortAndUser() {
        val yaml = "profiles:\n  - type: ssh\n    name: h\n    options:\n      host: example.com\n"
        val out = TabbyYamlParser.parseSshProfiles(yaml, "o")
        assertEquals(1, out.size)
        assertEquals(22, out[0].port)
        assertEquals("root", out[0].username)
    }

    @Test fun malformedYamlDoesNotCrash() {
        assertTrue(TabbyYamlParser.parseSshProfiles(":\n: [unclosed", "o").isEmpty())
    }
}
