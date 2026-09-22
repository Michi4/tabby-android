package at.websters.tabbyandroid.data.sync

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/**
 * Vault interop: the fixture `vault_vector.json` was encrypted with Node.js
 * using tabby-core's exact `encryptVault` (PBKDF2-SHA512 x100000, AES-256-CBC)
 * over FAKE data with a throwaway password — safe to commit, proves we read
 * what desktop Tabby writes.
 */
class VaultCryptoTest {
    private fun vector(): Pair<VaultCrypto.StoredVault, String> {
        val stream: java.io.InputStream =
            this::class.java.getResourceAsStream("/vault_vector.json")
                ?: this::class.java.classLoader!!.getResourceAsStream("vault_vector.json")!!
        val raw: String = stream.bufferedReader().readText()
        assertTrue("vector not JSON?", raw.trim().startsWith("{"))
        val json = kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject
        val vaultEl = json["vault"]
        assertTrue("vault not object: $vaultEl", vaultEl is kotlinx.serialization.json.JsonObject)
        val stored = VaultCrypto.parseStored(
            kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject.toPlainMap()
        )
        assertTrue("parseStored returned null", stored != null)
        val pw = (json["password"] as? JsonPrimitive)?.content
        assertTrue("no password", pw != null)
        return stored!! to pw!!
    }

    @Test fun nodeVectorDecryptsAndParses() {
        val (stored, pw) = vector()
        assertEquals(1, stored.version)
        val content = VaultCrypto.decrypt(stored, pw)
        val profiles = TabbyYamlParser.parseSshProfilesFromMap(content.config, "o")
        assertEquals(1, profiles.size)
        assertEquals("vector-host", profiles[0].name)
        assertEquals("vector.example.com", profiles[0].host)
        assertEquals(2222, profiles[0].port)
        assertEquals("vec", profiles[0].username)
        val groups = TabbyYamlParser.parseGroupsFull(content.config)
        assertEquals(1, groups.size)
        assertEquals("Vector", groups[0].name)
        assertEquals(1, content.secrets.size)
    }

    @Test fun jsonNullStaysNullInsteadOfStringNull() {
        assertNull(JsonNull.toPlain())
        assertEquals("x", JsonPrimitive("x").toPlain())
    }

    @Test fun wrongPassphraseFails() {
        val (stored, _) = vector()
        try {
            VaultCrypto.decrypt(stored, "definitely-wrong")
            fail("must throw")
        } catch (e: VaultBadPassphraseException) {
            assertTrue(e.message!!.contains("Incorrect"))
        }
    }

    @Test fun roundTrip() {
        val config = mapOf<String, Any?>(
            "version" to 7L,
            "profiles" to listOf(
                mapOf("type" to "ssh", "name" to "h", "id" to "x",
                    "options" to mapOf("host" to "h.example", "port" to 22, "user" to "u"))
            ),
        )
        val stored = VaultCrypto.encrypt(config, emptyList(), "pw-123")
        assertEquals(1, stored.version)
        val back = VaultCrypto.decrypt(stored, "pw-123")
        assertEquals(
            "h.example",
            (((back.config["profiles"] as List<*>)[0] as Map<*, *>)["options"] as Map<*, *>)["host"],
        )
    }

    @Test fun badVersionRejected() {
        val (stored, pw) = vector()
        try {
            VaultCrypto.decrypt(stored.copy(version = 99), pw)
            fail("must throw")
        } catch (e: VaultFormatException) {
            assertTrue(e.message!!.contains("99"))
        }
    }

    @Test fun emptyPassphraseRejected() {
        val (stored, _) = vector()
        try {
            VaultCrypto.decrypt(stored, "")
            fail("must throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("passphrase"))
        }
    }

    @Test fun corruptHexIsFormatErrorNotPassphraseError() {
        val (stored, pw) = vector()
        try {
            VaultCrypto.decrypt(stored.copy(saltHex = "zz"), pw)
            fail("must throw")
        } catch (e: VaultFormatException) {
            assertTrue(e.message!!.contains("Invalid vault"))
        }
    }

    @Test fun corruptBase64IsFormatError() {
        val (stored, pw) = vector()
        try {
            VaultCrypto.decrypt(stored.copy(contentsB64 = "!!!not-base64!!!"), pw)
            fail("must throw")
        } catch (e: VaultFormatException) {
            assertTrue(e.message!!.contains("Invalid vault"))
        }
    }

    @Test fun secretHoldersRedactToString() {
        val (stored, pw) = vector()
        assertFalse(stored.toString().contains(stored.contentsB64))
        val content = VaultCrypto.decrypt(stored, pw)
        assertFalse(content.toString().contains("vector.example.com"))
    }

    @Test fun encryptYieldsYamlSafeSalts() {
        // Salts of only [0-9e] are dumped unquoted by SnakeYAML and read back
        // by desktop js-yaml as numbers/Infinity ("keySalt: .inf"). Every
        // generated salt/iv must contain one of a,b,c,d,f.
        repeat(50) {
            val s = VaultCrypto.encrypt(mapOf("version" to 7), emptyList(), "pw-123")
            assertTrue("unsafe salt ${s.saltHex}", s.saltHex.any { c -> c == 'a' || c == 'b' || c == 'c' || c == 'd' || c == 'f' })
            assertTrue("unsafe iv ${s.ivHex}", s.ivHex.any { c -> c == 'a' || c == 'b' || c == 'c' || c == 'd' || c == 'f' })
        }
    }
}
