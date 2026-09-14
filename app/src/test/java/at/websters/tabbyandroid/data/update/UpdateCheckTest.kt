package at.websters.tabbyandroid.data.update

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class UpdateCheckTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun base() = server.url("/").toString().trimEnd('/')

    private fun releaseJson(
        tag: String = "v9.9.9",
        body: String = "notes here",
        assets: String = """[{"name":"tabby-android-9.9.9.apk","browser_download_url":"https://example.com/a.apk"}]""",
    ) = """{"tag_name":"$tag","body":"$body","assets":$assets}"""

    @Test fun parseVersionTag() {
        assertEquals(Triple(1, 4, 3), parseVersionTag("v1.4.3"))
        assertEquals(Triple(1, 4, 3), parseVersionTag("1.4.3"))
        assertEquals(Triple(10, 0, 1), parseVersionTag("V10.0.1"))
        assertNull(parseVersionTag("latest"))
        assertNull(parseVersionTag("1.4"))
        assertNull(parseVersionTag("1.4.x"))
        assertNull(parseVersionTag(""))
    }

    @Test fun isNewerThan() {
        assertTrue(isNewerThan("v1.4.4", "1.4.3"))
        assertTrue(isNewerThan("v2.0.0", "1.9.9"))
        assertFalse(isNewerThan("v1.4.3", "1.4.3"))
        assertFalse(isNewerThan("v1.4.2", "1.4.3"))
        assertFalse(isNewerThan("bogus", "1.4.3"))
        assertFalse(isNewerThan("v1.4.4", "bogus"))
    }

    @Test fun newerYieldsAvailable() = runTest {
        server.enqueue(MockResponse().setBody(releaseJson()).setResponseCode(200))
        val r = checkForUpdate("1.0.0", base())
        assertTrue(r is UpdateState.Available)
        val info = (r as UpdateState.Available).info
        assertEquals("9.9.9", info.version)
        assertEquals("v9.9.9", info.tag)
        assertEquals("https://example.com/a.apk", info.apkUrl)
        assertEquals("notes here", info.notes)
        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.endsWith("/repos/Michi4/tabby-android/releases/latest"))
    }

    @Test fun currentWhenSameOrOlder() = runTest {
        server.enqueue(MockResponse().setBody(releaseJson(tag = "v1.0.0")).setResponseCode(200))
        assertTrue(checkForUpdate("1.0.0", base()) is UpdateState.Current)
        server.enqueue(MockResponse().setBody(releaseJson(tag = "v0.9.0")).setResponseCode(200))
        assertTrue(checkForUpdate("1.0.0", base()) is UpdateState.Current)
    }

    @Test fun noApkIsFailure() = runTest {
        server.enqueue(MockResponse().setBody(releaseJson(assets = "[]")).setResponseCode(200))
        val r = checkForUpdate("1.0.0", base())
        assertTrue(r is UpdateState.Failed)
    }

    @Test fun httpErrorsAreFailures() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertTrue(checkForUpdate("1.0.0", base()) is UpdateState.Failed)
        server.enqueue(MockResponse().setResponseCode(500))
        val r = checkForUpdate("1.0.0", base())
        assertTrue(r is UpdateState.Failed)
        assertTrue((r as UpdateState.Failed).message.contains("500"))
    }

    @Test fun garbageBodyIsFailure() = runTest {
        server.enqueue(MockResponse().setBody("not json").setResponseCode(200))
        assertTrue(checkForUpdate("1.0.0", base()) is UpdateState.Failed)
    }

    @Test fun parseUpdateCheckRecord() {
        assertEquals(0L to null, parseUpdateCheck(null))
        assertEquals(0L to null, parseUpdateCheck("garbage"))
        val (at, none) = parseUpdateCheck("12345|")
        assertEquals(12345L, at)
        assertNull(none)
        val info = ReleaseInfo("9.9.9", "v9.9.9", "https://example.com/a.apk", "notes|with|pipes")
        val js = kotlinx.serialization.json.Json.encodeToString(ReleaseInfo.serializer(), info)
        // pipes inside JSON must survive the record format
        assertEquals(999L to info, parseUpdateCheck("999|$js"))
    }
}
