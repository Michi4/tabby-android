package at.websters.tabbyandroid.data.sync

import at.websters.tabbyandroid.data.model.SshProfile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Vault end-to-end (pull resolve + push rebuild) against the Node-generated
 * fixture (fake data, throwaway password — safe to commit).
 */
class VaultSyncTest {
    private fun vaultYaml(): Triple<String, String, VaultCrypto.StoredVault> {
        val raw = this::class.java.classLoader!!.getResourceAsStream("vault_vector.json")!!
            .bufferedReader().readText()
        val json = kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject
        val inner = (json["vault"] as JsonObject).toPlainMap()
        val stored = VaultCrypto.parseStored(mapOf("vault" to inner))!!
        val pw = (json["password"] as JsonPrimitive).content
        val yaml = buildString {
            appendLine("vault:")
            appendLine("  version: ${inner["version"]}")
            appendLine("  contents: ${inner["contents"]}")
            appendLine("  keySalt: ${inner["keySalt"]}")
            appendLine("  iv: ${inner["iv"]}")
            appendLine("encrypted: true")
        }
        return Triple(yaml, pw, stored)
    }

    @Test fun lockedWithoutPassphrase() {
        val (yaml, _, _) = vaultYaml()
        val r = VaultSync.resolvePull(yaml, "o", null)
        assertTrue(r is VaultSync.PullResolution.Locked)
    }

    @Test fun unlocksWithPassphrase() {
        val (yaml, pw, _) = vaultYaml()
        val r = VaultSync.resolvePull(yaml, "o", pw)
        assertTrue(r is VaultSync.PullResolution.Ready)
        val ready = r as VaultSync.PullResolution.Ready
        assertEquals(1, ready.profiles.size)
        assertEquals("vector-host", ready.profiles[0].name)
        assertEquals("o", ready.profiles[0].origin)
    }

    @Test fun wrongPassphraseFails() {
        val (yaml, _, _) = vaultYaml()
        val r = VaultSync.resolvePull(yaml, "o", "nope")
        assertTrue(r is VaultSync.PullResolution.Failed)
        assertTrue((r as VaultSync.PullResolution.Failed).message.contains("Incorrect"))
    }

    @Test fun corruptVaultEnvelopeFailsCleanly() {
        val (yaml, pw, _) = vaultYaml()
        val broken = yaml.replace("keySalt:", "keySalt: zz")
        val r = VaultSync.resolvePull(broken, "o", pw)
        assertTrue(r is VaultSync.PullResolution.Failed)
        // no raw parser text may leak into the message
        assertFalse((r as VaultSync.PullResolution.Failed).message.contains("zz"))
    }

    @Test fun corruptVaultUploadFailsCleanly() {
        val (yaml, pw, _) = vaultYaml()
        val broken = yaml.replace("keySalt:", "keySalt: zz")
        val r = VaultSync.buildUpload(broken, emptyList(), emptySet(), pw)
        assertTrue(r is VaultSync.PushResolution.Failed)
    }

    @Test fun pushRoundTripIntoVault() {
        val (yaml, pw, _) = vaultYaml()
        val extra = SshProfile(
            id = "manual:1", name = "new", host = "new.example.com",
            port = 22, username = "root", origin = "manual",
        )
        val push = VaultSync.buildUpload(yaml, listOf(extra), emptySet(), pw)
        assertTrue(push is VaultSync.PushResolution.Ready)
        val out = (push as VaultSync.PushResolution.Ready).content
        // still an encrypted envelope (no plaintext leak!)
        assertTrue(out.contains("vault:"))
        assertTrue(!out.contains("new.example.com"))
        // ...that decrypts to old + new profiles
        val reparsed = VaultSync.resolvePull(out, "o", pw)
        assertTrue(reparsed is VaultSync.PullResolution.Ready)
        val names = (reparsed as VaultSync.PullResolution.Ready).profiles.map { it.name }.toSet()
        assertEquals(setOf("vector-host", "new"), names)
    }

    @Test fun pushLockedWithoutPassphrase() {
        val (yaml, _, _) = vaultYaml()
        val r = VaultSync.buildUpload(yaml, emptyList(), emptySet(), null)
        assertTrue(r is VaultSync.PushResolution.Locked)
    }

    @Test fun plainContentBypassesVault() {
        val plain = "version: 7\nprofiles:\n  - type: ssh\n    name: h\n    options:\n      host: example.com\n"
        val r = VaultSync.resolvePull(plain, "o", null)
        assertTrue(r is VaultSync.PullResolution.Ready)
        assertEquals(1, (r as VaultSync.PullResolution.Ready).profiles.size)
    }

    @Test fun invalidRemoteConfigDoesNotResolveAsEmptyPull() {
        val r = VaultSync.resolvePull("profiles: [unclosed", "o", null)
        assertTrue(r is VaultSync.PullResolution.Failed)
        assertTrue((r as VaultSync.PullResolution.Failed).message.contains("unreadable"))
    }

    @Test fun invalidRemoteConfigDoesNotBuildUploadFromEmptyMap() {
        val r = VaultSync.buildUpload("version: 7\nprofiles: [unclosed", emptyList(), emptySet(), null)
        assertTrue(r is VaultSync.PushResolution.Failed)
        assertTrue((r as VaultSync.PushResolution.Failed).message.contains("unreadable"))
    }

    @Test fun emptyRemoteConfigStillUploads() {
        val r = VaultSync.buildUpload("{}", emptyList(), emptySet(), null)
        assertTrue(r is VaultSync.PushResolution.Ready)
    }

    @Test fun numericSaltUploadRefusesInsteadOfCleartextMerge() {
        // SnakeYAML loads unquoted "12e34…" as Double, so parseStored fails.
        // Must be Failed (never a cleartext merge over the vault).
        val yaml = """
            vault:
              version: 1
              contents: dGVzdA==
              keySalt: 12e34000000000000
              iv: 9af1497337f8d598ceffcb75ee597c6e
            encrypted: true
        """.trimIndent()
        val r = VaultSync.buildUpload(yaml, emptyList(), emptySet(), "pw")
        assertTrue("expected Failed, got $r", r is VaultSync.PushResolution.Failed)
        assertTrue((r as VaultSync.PushResolution.Failed).message.contains("Invalid vault"))
    }

    @Test fun numericSaltPullFailsInsteadOfEmpty() {
        val yaml = """
            vault:
              version: 1
              contents: dGVzdA==
              keySalt: 12e34000000000000
              iv: 9af1497337f8d598ceffcb75ee597c6e
            encrypted: true
        """.trimIndent()
        val r = VaultSync.resolvePull(yaml, "o", "pw")
        assertTrue("expected Failed, got $r", r is VaultSync.PullResolution.Failed)
    }

    @Test fun dumpQuotesNumericVaultScalars() {
        val out = TabbyYamlSerializer.dumpYaml(
            mapOf(
                "vault" to mapOf(
                    "version" to 1,
                    "contents" to "dGVzdA==",
                    "keySalt" to "12e34000000000000",
                    "iv" to "9af1497337f8d598ceffcb75ee597c6e",
                ),
                "encrypted" to true,
            )
        )
        assertTrue(out.contains("keySalt: '12e34000000000000'"))
        // …and it loads back as strings, not numbers
        val back = TabbyYamlParser.loadContentMap(out)!!
        @Suppress("UNCHECKED_CAST")
        val vault = back["vault"] as Map<String, Any?>
        assertEquals("12e34000000000000", vault["keySalt"])
        assertEquals("9af1497337f8d598ceffcb75ee597c6e", vault["iv"])
    }
}
