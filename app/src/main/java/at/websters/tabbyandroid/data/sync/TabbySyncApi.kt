package at.websters.tabbyandroid.data.sync

import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.Path

interface TabbySyncService {
    @GET("api/1/configs")
    suspend fun listConfigs(): List<ApiConfig>

    @GET("api/1/configs/{id}")
    suspend fun getConfig(@Path("id") id: Long): ApiConfig

    @GET("api/1/user")
    suspend fun getUser(): ApiUser

    /**
     * Same call Tabby desktop's ConfigSyncService.updateConfig makes.
     * Supported by tabby-web and the rtabby-web-api drop-in.
     */
    @PATCH("api/1/configs/{id}")
    suspend fun updateConfig(
        @Path("id") id: Long,
        @Body body: UpdateConfigBody,
    ): ApiConfig
}

object TabbySyncApiFactory {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun requireHttps(host: String): String {
        val h = host.trim()
        require(h.startsWith("https://", ignoreCase = true)) {
            "Sync host must use HTTPS (got: $host). Plain HTTP would expose SSH config to MITM."
        }
        return h.trimEnd('/')
    }

    /**
     * Forgiving normalization for the host field: bare `example.com` becomes
     * `https://example.com`. Explicit `http://` is still rejected (see above),
     * and TLS certificate failures surface as errors (never silently ignored).
     */
    fun normalizeHost(raw: String): String {
        var h = raw.trim()
        require(h.isNotBlank()) { "Enter your Tabby Web instance URL" }
        if (!h.contains("://")) h = "https://$h"
        return requireHttps(h)
    }

    /** Human-friendly sync errors: downtime, TLS, auth, timeouts. */
    fun friendlyError(e: Throwable): String = when (e) {
        is IllegalArgumentException -> e.message ?: "Invalid sync settings"
        is javax.net.ssl.SSLHandshakeException ->
            "TLS certificate problem: the server's certificate isn't trusted " +
                "(expired, wrong host, or self-signed). Fix the server certificate and retry."
        is java.net.UnknownHostException ->
            "Can't reach this host (DNS/network). The server may be restarting — retry in a bit."
        is java.net.SocketTimeoutException, is java.net.ConnectException ->
            "Connection timed out. The server may be restarting — retry in a bit."
        is retrofit2.HttpException -> when (e.code()) {
            401, 403 -> "Invalid sync token (HTTP ${e.code()}). Copy it fresh from Tabby Web settings."
            404 -> "Not found (HTTP 404). Check the host URL and selected config."
            else -> "Server error (HTTP ${e.code()}). Retry in a bit."
        }
        else -> e.message ?: e.javaClass.simpleName
    }

    fun create(host: String, token: String): TabbySyncService {
        val base = requireHttps(host) + "/"
        val auth = Interceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer ${token.trim()}")
                    .build()
            )
        }
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        val client = OkHttpClient.Builder()
            .addInterceptor(auth)
            .addInterceptor(logging)
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl(base)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(TabbySyncService::class.java)
    }
}
