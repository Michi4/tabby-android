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
        // Genuinely destroyed salt (a re-saved float, not intact hex): rescue
        // is impossible. Must be Failed (never a cleartext merge over the
        // vault), naming the field and pointing at backups.
        val yaml = """
            vault:
              version: 1
              contents: dGVzdA==
              keySalt: 1.2e+35
              iv: 9af1497337f8d598ceffcb75ee597c6e
            encrypted: true
        """.trimIndent()
        val r = VaultSync.buildUpload(yaml, emptyList(), emptySet(), "pw")
        assertTrue("expected Failed, got $r", r is VaultSync.PushResolution.Failed)
        val msg = (r as VaultSync.PushResolution.Failed).message
        assertTrue(msg, msg.contains("Invalid vault") && msg.contains("keySalt") && msg.contains("backup"))
    }

    @Test fun numericSaltPullFailsInsteadOfEmpty() {
        val yaml = """
            vault:
              version: 1
              contents: dGVzdA==
              keySalt: 1.2e+35
              iv: 9af1497337f8d598ceffcb75ee597c6e
            encrypted: true
        """.trimIndent()
        val r = VaultSync.resolvePull(yaml, "o", "pw")
        assertTrue("expected Failed, got $r", r is VaultSync.PullResolution.Failed)
        // …and the message must name the damaged field and point at backups
        val msg = (r as VaultSync.PullResolution.Failed).message
        assertTrue(msg, msg.contains("keySalt") && msg.contains("number") && msg.contains("backup"))
    }

    /**
     * The user's exact scenario: a writer saved the TRUE salt unquoted, so
     * YAML loads a Double — but the literal `12e34…` text is intact. The
     * vault must OPEN, not fail. Vault bytes are built here with the same
     * algorithm desktop uses (PBKDF2-SHA512 x100000, AES-256-CBC).
     */
    private fun rawVaultYaml(saltHex: String, ivHex: String, pw: String, profileName: String): String {
        fun hex(s: String): ByteArray =
            s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        val key = factory.generateSecret(
            javax.crypto.spec.PBEKeySpec(pw.toCharArray(), hex(saltHex), 100000, 256)
        ).encoded
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.IvParameterSpec(hex(ivHex)),
        )
        val inner = """{"config":{"profiles":[{"type":"ssh","name":"$profileName","options":{"host":"example.com","port":22,"user":"u"}}]},"secrets":[]}"""
        val contents = java.util.Base64.getEncoder()
            .encodeToString(cipher.doFinal(inner.toByteArray(Charsets.UTF_8)))
        return "vault:\n  version: 1\n  contents: $contents\n  keySalt: $saltHex\n  iv: $ivHex\nencrypted: true\n"
    }

    @Test fun intactHexSaltPullOpensAfterRescue() {
        // "1234567890123e45" is valid 8-byte hex that YAML reads as Double.
        val yaml = rawVaultYaml("1234567890123e45", "9af1497337f8d598ceffcb75ee597c6e", "correct-horse", "rescued-host")
        val r = VaultSync.resolvePull(yaml, "o", "correct-horse")
        assertTrue("expected Ready, got $r", r is VaultSync.PullResolution.Ready)
        assertEquals("rescued-host", (r as VaultSync.PullResolution.Ready).profiles[0].name)
    }

    @Test fun intactHexSaltWrongPassphraseStillSaysSo() {
        // Rescue composes with normal crypto errors: right envelope, wrong
        // password must say "Incorrect", not "Invalid vault".
        val yaml = rawVaultYaml("1234567890123e45", "9af1497337f8d598ceffcb75ee597c6e", "correct-horse", "rescued-host")
        val r = VaultSync.resolvePull(yaml, "o", "wrong")
        assertTrue("expected Failed, got $r", r is VaultSync.PullResolution.Failed)
        assertTrue((r as VaultSync.PullResolution.Failed).message.contains("Incorrect"))
    }

    @Test fun intactHexSaltPushRoundTrips() {
        // Push over a rescued vault: decrypt with the rescued salt, then
        // re-encrypt cleanly (fresh YAML-safe salt, quoted scalars).
        val yaml = rawVaultYaml("1234567890123e45", "9af1497337f8d598ceffcb75ee597c6e", "correct-horse", "rescued-host")
        val r = VaultSync.buildUpload(yaml, emptyList(), emptySet(), "correct-horse")
        assertTrue("expected Ready, got $r", r is VaultSync.PushResolution.Ready)
        val reopen = VaultSync.resolvePull((r as VaultSync.PushResolution.Ready).content, "o", "correct-horse")
        assertTrue("expected Ready, got $reopen", reopen is VaultSync.PullResolution.Ready)
        assertEquals("rescued-host", (reopen as VaultSync.PullResolution.Ready).profiles[0].name)
    }

    @Test fun infSaltPullExplainsNumberDamage() {
        val yaml = """
            vault:
              version: 1
              contents: dGVzdA==
              keySalt: .inf
              iv: 9af1497337f8d598ceffcb75ee597c6e
            encrypted: true
        """.trimIndent()
        val r = VaultSync.resolvePull(yaml, "o", "pw")
        assertTrue("expected Failed, got $r", r is VaultSync.PullResolution.Failed)
        val msg = (r as VaultSync.PullResolution.Failed).message
        assertTrue(msg, msg.contains("keySalt") && msg.contains("number"))
    }

    @Test fun missingIvUploadNamesField() {
        val yaml = """
            vault:
              version: 1
              contents: dGVzdA==
              keySalt: 9af1497337f8d59
            encrypted: true
        """.trimIndent()
        val r = VaultSync.buildUpload(yaml, emptyList(), emptySet(), "pw")
        assertTrue("expected Failed, got $r", r is VaultSync.PushResolution.Failed)
        assertTrue((r as VaultSync.PushResolution.Failed).message.contains("iv"))
    }

    @Test fun stringVersionPullOpens() {
        // A writer that quoted `version: "1"` writes a valid vault.
        val (yaml, pw, _) = vaultYaml()
        val quoted = yaml.replace("version: 1", "version: \"1\"")
        val r = VaultSync.resolvePull(quoted, "o", pw)
        assertTrue("expected Ready, got $r", r is VaultSync.PullResolution.Ready)
        assertEquals("vector-host", (r as VaultSync.PullResolution.Ready).profiles[0].name)
    }

    @Test fun wrappedContentsPullOpens() {
        // Base64 folded across lines (plain multiline scalar loads with
        // spaces) is formatting, not damage: the vault must still open.
        val (yaml, pw, _) = vaultYaml()
        val lines = yaml.lines().toMutableList()
        val idx = lines.indexOfFirst { it.trimStart().startsWith("contents:") }
        val b64 = lines[idx].substringAfter("contents:").trim()
        lines[idx] = "  contents: " + b64.chunked(64).joinToString("\n    ")
        val r = VaultSync.resolvePull(lines.joinToString("\n"), "o", pw)
        assertTrue("expected Ready, got $r", r is VaultSync.PullResolution.Ready)
        assertEquals("vector-host", (r as VaultSync.PullResolution.Ready).profiles[0].name)
    }

    @Test fun wrappedContentsUploadRoundTrips() {
        // Push over a wrapped-contents vault: decrypt must tolerate the
        // folding, then re-encrypt cleanly.
        val (yaml, pw, _) = vaultYaml()
        val lines = yaml.lines().toMutableList()
        val idx = lines.indexOfFirst { it.trimStart().startsWith("contents:") }
        val b64 = lines[idx].substringAfter("contents:").trim()
        lines[idx] = "  contents: " + b64.chunked(64).joinToString("\n    ")
        val r = VaultSync.buildUpload(lines.joinToString("\n"), emptyList(), emptySet(), pw)
        assertTrue("expected Ready, got $r", r is VaultSync.PushResolution.Ready)
        // …and the rebuilt upload opens again with the same passphrase
        val reopen = VaultSync.resolvePull((r as VaultSync.PushResolution.Ready).content, "o", pw)
        assertTrue("expected Ready, got $reopen", reopen is VaultSync.PullResolution.Ready)
    }

    @Test fun dumpQuotesNumericVaultScalars() {
        val out = TabbyYamlSerializer.dumpYaml(
            mapOf(
                "vault" to mapOf(
                    "version" to 1,
                    "contents" to "dGVzdA==",
                    "keySalt" to "1234567890123e45",
                    "iv" to "9af1497337f8d598ceffcb75ee597c6e",
                ),
                "encrypted" to true,
            )
        )
        assertTrue(out.contains("keySalt: '1234567890123e45'"))
        // …and it loads back as strings, not numbers
        val back = TabbyYamlParser.loadContentMap(out)!!
        @Suppress("UNCHECKED_CAST")
        val vault = back["vault"] as Map<String, Any?>
        assertEquals("1234567890123e45", vault["keySalt"])
        assertEquals("9af1497337f8d598ceffcb75ee597c6e", vault["iv"])
    }
}
