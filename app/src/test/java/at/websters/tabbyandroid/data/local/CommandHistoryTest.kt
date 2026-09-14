package at.websters.tabbyandroid.data.local

import org.junit.Assert.*
import org.junit.Test

class CommandHistoryTest {
    @Test fun recordsAndCounts() {
        var h = recordCommand(emptyList(), "git pull")
        assertEquals(1, h.size)
        assertEquals(1, h[0].count)
        h = recordCommand(h, "git pull", now = h[0].lastUsed + 10)
        assertEquals(1, h.size)
        assertEquals(2, h[0].count)
    }

    @Test fun sensitiveLinesSkipped() {
        assertTrue(isSensitiveCommand("mysql -ppassword123"))
        assertTrue(isSensitiveCommand("export API_KEY=xyz"))
        assertTrue(isSensitiveCommand("sshpass -p foo ssh x"))
        assertFalse(isSensitiveCommand("git pull"))
        val h = recordCommand(emptyList(), "export TOKEN=abc")
        assertTrue(h.isEmpty())
        val h2 = recordCommand(emptyList(), "  ")
        assertTrue(h2.isEmpty())
    }

    @Test fun rankByFrequencyThenRecency() {
        var h = emptyList<CmdEntry>()
        h = recordCommand(h, "git pull", now = 1)
        h = recordCommand(h, "git push", now = 2)
        h = recordCommand(h, "git push", now = 3)
        h = recordCommand(h, "git pull", now = 4)
        // pull has count 2 vs push 2... make pull win by count:
        h = recordCommand(h, "git pull", now = 5)
        assertEquals(listOf("git pull", "git push"), rankSuggestions(h, "git"))
        // tie → more recent first
        var h2 = emptyList<CmdEntry>()
        h2 = recordCommand(h2, "docker ps", now = 1)
        h2 = recordCommand(h2, "docker images", now = 2)
        assertEquals(listOf("docker images", "docker ps"), rankSuggestions(h2, "docker"))
    }

    @Test fun prefixGating() {
        val h = recordCommand(emptyList(), "ls -la")
        assertTrue(rankSuggestions(h, "").isEmpty())
        assertTrue(rankSuggestions(h, "l").isEmpty())
        assertEquals(listOf("ls -la"), rankSuggestions(h, "ls"))
        // exact full line is not a suggestion for itself
        assertTrue(rankSuggestions(h, "ls -la").isEmpty())
        assertTrue(rankSuggestions(h, "zz").isEmpty())
        // leading spaces are trimmed before matching
        assertEquals(listOf("ls -la"), rankSuggestions(h, "  ls"))
    }

    @Test fun capped() {
        var h = emptyList<CmdEntry>()
        repeat(250) { h = recordCommand(h, "cmd$it", now = it.toLong()) }
        assertEquals(200, h.size)
    }

    @Test fun macroRoundTrip() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val list = listOf(Macro("update", "sudo apt update"))
        val raw = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(Macro.serializer()), list
        )
        val back = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(Macro.serializer()), raw
        )
        assertEquals(list, back)
    }
}
