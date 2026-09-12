package at.websters.tabbyandroid.data.sync

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class SyncApiTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    @Test fun normalizeHostAutoHttps() {
        assertEquals("https://example.com", TabbySyncApiFactory.normalizeHost("example.com"))
        assertEquals("https://example.com", TabbySyncApiFactory.normalizeHost("  example.com/ "))
        assertEquals("https://example.com/sub", TabbySyncApiFactory.normalizeHost("example.com/sub"))
        try {
            TabbySyncApiFactory.normalizeHost("http://example.com")
            fail("explicit http must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("HTTPS"))
        }
        try {
            TabbySyncApiFactory.normalizeHost("   ")
            fail("blank must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("URL"))
        }
    }

    @Test fun friendlyErrors() {
        assertTrue(
            TabbySyncApiFactory.friendlyError(java.net.UnknownHostException("dns"))
                .contains("restarting")
        )
        assertTrue(
            TabbySyncApiFactory.friendlyError(javax.net.ssl.SSLHandshakeException("bad cert"))
                .contains("TLS")
        )
        assertTrue(
            TabbySyncApiFactory.friendlyError(java.net.SocketTimeoutException("slow"))
                .contains("timed out")
        )
    }

    @Test fun listConfigsSendsBearerToken() = runTest {
        server.enqueue(
            MockResponse()
                .setBody("""[{"id":1,"name":"Home","content":"{}"}]""")
                .addHeader("Content-Type", "application/json")
        )
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer tok")
                    .build()
            )
        }.build()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        val svc = retrofit.create(TabbySyncService::class.java)
        val list = svc.listConfigs()
        assertEquals(1, list.size)
        assertEquals(1L, list[0].id)
        val recorded = server.takeRequest()
        assertEquals("Bearer tok", recorded.getHeader("Authorization"))
        assertEquals("/api/1/configs", recorded.path)
    }
}
