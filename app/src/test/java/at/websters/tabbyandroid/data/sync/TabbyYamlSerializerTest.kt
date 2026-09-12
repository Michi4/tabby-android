package at.websters.tabbyandroid.data.sync

import at.websters.tabbyandroid.data.model.SshProfile
import org.junit.Assert.*
import org.junit.Test

class TabbyYamlSerializerTest {

    private fun sample(id: String = "ssh:custom:h:1") = SshProfile(
        id = id,
        name = "myhost",
        host = "example.com",
        port = 2222,
        username = "bob",
        authType = "publicKey",
        origin = "manual",
    )

    @Test fun roundTrip() {
        val p = sample()
        val merged = TabbyYamlSerializer.merge("{}", listOf(p))
        val back = TabbyYamlParser.parseSshProfiles(merged, "o")
        assertEquals(1, back.size)
        val b = back[0]
        assertEquals("myhost", b.name)
        assertEquals("example.com", b.host)
        assertEquals(2222, b.port)
        assertEquals("bob", b.username)
        assertEquals("publicKey", b.authType)
        assertEquals(p.id, b.id)
    }

    @Test fun mergePreservesUnmanagedAndDropsTombstones() {
        val remote = """
            version: 7
            hotkey: Ctrl+Alt+T
            profiles:
              - type: ssh
                name: desktop-only
                id: ssh:custom:desk:1
                options: {host: 10.0.0.1, port: 22, user: root}
              - type: ssh
                name: stale
                id: ssh:custom:stale:9
                options: {host: 10.0.0.9, port: 22, user: root}
              - type: ssh
                name: mine-old
                id: ssh:custom:mine:2
                options: {host: 10.0.0.2, port: 22, user: root}
        """.trimIndent()
        val mine = SshProfile("ssh:custom:mine:2", "mine-new", "10.0.0.3", 22, "root", origin = "o")
        val merged = TabbyYamlSerializer.merge(remote, listOf(mine), setOf("ssh:custom:stale:9"))
        assertTrue(merged.contains("hotkey")) // unknown top-level key preserved
        val back = TabbyYamlParser.parseSshProfiles(merged, "o")
        val byId = back.associateBy { it.id }
        assertTrue(byId.containsKey("ssh:custom:desk:1")) // unmanaged kept
        assertFalse(byId.containsKey("ssh:custom:stale:9")) // tombstoned gone
        assertEquals("mine-new", byId["ssh:custom:mine:2"]?.name) // replaced
        assertEquals("10.0.0.3", byId["ssh:custom:mine:2"]?.host)
    }

    @Test fun mergeIntoEmptyCreatesVersion() {
        val merged = TabbyYamlSerializer.merge("", listOf(sample()))
        assertTrue(merged.contains("version"))
        assertEquals(1, TabbyYamlParser.parseSshProfiles(merged, "o").size)
    }

    @Test fun accountIdFromOrigin() {
        assertEquals("acc1", TabbyYamlSerializer.accountIdFromOrigin("tabby:acc1:5"))
        assertEquals(null, TabbyYamlSerializer.accountIdFromOrigin("manual"))
        assertEquals("tabby:acc1:5", TabbyYamlSerializer.originFor("acc1", 5))
    }

    @Test fun deepMergePreservesDesktopOnlyFields() {
        val remote = """
            version: 7
            profiles:
              - type: ssh
                name: srv
                id: ssh:custom:srv:1
                icon: fas fa-server
                options:
                  host: 10.0.0.1
                  port: 22
                  user: root
                  privateKeys: ['file:///home/u/.ssh/id_ed25519']
                  scripts: ['echo hi']
                  forwardedPorts: [{host: 127.0.0.1, port: 8080}]
        """.trimIndent()
        val mine = SshProfile("ssh:custom:srv:1", "srv-renamed", "10.0.0.2", 2222, "admin", origin = "o")
        val back = TabbyYamlParser.parseSshProfiles(
            TabbyYamlSerializer.merge(remote, listOf(mine)), "o"
        )
        assertEquals(1, back.size)
        assertEquals("srv-renamed", back[0].name)
        assertEquals("10.0.0.2", back[0].host)
        assertEquals(2222, back[0].port)
        // raw YAML still carries the desktop-only fields
        val merged = TabbyYamlSerializer.merge(remote, listOf(mine))
        assertTrue(merged.contains("id_ed25519"))
        assertTrue(merged.contains("echo hi"))
        assertTrue(merged.contains("forwardedPorts"))
        assertTrue(merged.contains("fas fa-server"))
        // display-only groupName is never written back
        val withGroup = mine.copy(group = "g1", groupName = "Home")
        assertFalse(TabbyYamlSerializer.merge(remote, listOf(withGroup)).contains("groupName"))
    }

    @Test fun groupNamesResolved() {
        val yaml = """
            version: 7
            groups:
              - id: g1
                name: Home
            profiles:
              - type: ssh
                name: h
                id: ssh:custom:h:1
                group: g1
                options: {host: 10.0.0.1}
        """.trimIndent()
        val back = TabbyYamlParser.parseSshProfiles(yaml, "o")
        assertEquals(1, back.size)
        assertEquals("g1", back[0].group)
        assertEquals("Home", back[0].groupName)
    }
}
