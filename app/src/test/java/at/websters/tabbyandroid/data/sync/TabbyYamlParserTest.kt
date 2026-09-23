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

    @Test fun literalNullGroupIsUngrouped() {
        val yaml = "profiles:\n  - type: ssh\n    name: h\n    group: 'null'\n    options:\n      host: example.com\n"
        val out = TabbyYamlParser.parseSshProfiles(yaml, "o")
        assertEquals(1, out.size)
        assertNull(out[0].group)
    }

    @Test fun malformedYamlDoesNotCrash() {
        assertTrue(TabbyYamlParser.parseSshProfiles(":\n: [unclosed", "o").isEmpty())
    }

    @Test fun emptyConfigsAreValidButCorruptContentIsNot() {
        assertFalse(TabbyYamlParser.isUnreadableConfigContent(""))
        assertFalse(TabbyYamlParser.isUnreadableConfigContent("{}"))
        assertFalse(TabbyYamlParser.isUnreadableConfigContent("version: 7\n"))
        assertTrue(TabbyYamlParser.isUnreadableConfigContent("profiles: [unclosed"))
        assertTrue(TabbyYamlParser.isUnreadableConfigContent("scalar"))
    }

    @Test fun javaDeserializationGadgetIsNeutralized() {
        // CVE-2022-1471 class: a hostile sync server (or config edit) must
        // never instantiate JVM classes. SafeConstructor rejects the tags;
        // the parse fails closed with zero profiles and no side effects.
        val evil = "profiles: !!javax.script.ScriptEngineManager " +
            "[!!java.net.URLClassLoader [[!!java.net.URL [\"http://127.0.0.1:9/x\"]]]]"
        assertTrue(TabbyYamlParser.parseSshProfiles(evil, "o").isEmpty())
        assertTrue(TabbyYamlParser.loadContentMap(evil) == null)
    }

    @Test(timeout = 10_000) fun billionLaughsIsCapped() {
        // 61 aliases in one collection — over SnakeYAML's default per-
        // collection alias cap. Must throw inside the parser (fail closed),
        // never expand, hang, or OOM. Anchored refs stay shared, so even an
        // exponential chain could not blow memory here; the cap + timeout
        // pin both failure modes.
        val lol = buildString {
            append("a: &a [\"x\"]\n")
            append("b: &b [")
            repeat(61) { append("*a,") }
            append("*a]\n")
            append("profiles: *b\n")
        }
        assertTrue(TabbyYamlParser.parseSshProfiles(lol, "o").isEmpty())
        // …and the alias cap must actually fire (null = compose threw).
        // If SnakeYAML ever raises its default, this fails and we pin the
        // cap explicitly in safeYaml() instead of trusting the default.
        assertTrue(TabbyYamlParser.loadContentMap(lol) == null)
    }

    @Test(timeout = 10_000) fun deepNestingDoesNotCrash() {
        val open = "[".repeat(500)
        val close = "]".repeat(500)
        val deep = "profiles: $open$close\n"
        // Either empty (rejected) or parsed — but it must return, not throw
        // StackOverflowError out of the test VM.
        try {
            TabbyYamlParser.parseSshProfiles(deep, "o")
        } catch (e: StackOverflowError) {
            throw AssertionError("deep nesting blew the stack", e)
        }
    }

    @Test fun oversizedScalarFailsClosed() {
        // Past the 3MB code-point limit: must be unreadable, never partial.
        val big = "profiles: " + "y".repeat(4 * 1024 * 1024) + "\n"
        assertTrue(TabbyYamlParser.isUnreadableConfigContent(big))
        assertTrue(TabbyYamlParser.parseSshProfiles(big, "o").isEmpty())
    }

    @Test fun duplicateKeysDoNotCrash() {
        val dup = "version: 7\nversion: 8\nprofiles: []\n"
        assertTrue(TabbyYamlParser.parseSshProfiles(dup, "o").isEmpty())
    }
}
