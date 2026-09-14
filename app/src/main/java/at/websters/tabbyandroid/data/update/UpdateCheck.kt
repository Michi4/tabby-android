package at.websters.tabbyandroid.data.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * In-app updates from GitHub releases (no store needed).
 * Public API, no auth: `GET /repos/Michi4/tabby-android/releases/latest`.
 * Pure version math + parsing is unit-tested; the network call is tested
 * against MockWebServer with an injectable base URL.
 */
@Serializable
data class ReleaseInfo(
    /** Normalized "1.4.3" (no leading v). */
    val version: String,
    val tag: String,
    val apkUrl: String,
    val notes: String,
)

sealed interface UpdateState {
    data object Current : UpdateState
    data class Available(val info: ReleaseInfo) : UpdateState
    data class Failed(val message: String) : UpdateState
}

private const val OWNER_REPO = "Michi4/tabby-android"

@Serializable
private data class GhAsset(
    val name: String = "",
    @SerialName("browser_download_url") val url: String = "",
)

@Serializable
private data class GhRelease(
    @SerialName("tag_name") val tag: String = "",
    val body: String? = null,
    val assets: List<GhAsset> = emptyList(),
)

/** "v1.4.3" / "1.4.3" -> (1, 4, 3); null when unparseable (never nag on those). */
fun parseVersionTag(tag: String): Triple<Int, Int, Int>? {
    val parts = tag.trim().removePrefix("v").removePrefix("V").split(".")
    if (parts.size != 3) return null
    val nums = parts.map { it.toIntOrNull() ?: return null }
    if (nums.any { it < 0 }) return null
    return Triple(nums[0], nums[1], nums[2])
}

/** True when [latestTag] parses AND is strictly newer than [current]. */
fun isNewerThan(latestTag: String, current: String): Boolean {
    val l = parseVersionTag(latestTag) ?: return false
    val c = parseVersionTag(current) ?: return false
    return compareValuesBy(l, c, { it.first }, { it.second }, { it.third }) > 0
}

/** Parses the stored `"<epoch>|<release-json>"` check record (pure, tested). */
internal fun parseUpdateCheck(raw: String?): Pair<Long, ReleaseInfo?> {
    if (raw == null) return 0L to null
    return runCatching {
        val sep = raw.indexOf('|')
        require(sep >= 0)
        val at = raw.substring(0, sep).toLong()
        val info = raw.substring(sep + 1).takeIf { it.isNotBlank() }?.let {
            json.decodeFromString<ReleaseInfo>(it)
        }
        at to info
    }.getOrDefault(0L to null)
}

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

private fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .writeTimeout(15, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS)
    .build()

suspend fun checkForUpdate(
    currentVersion: String,
    apiBase: String = "https://api.github.com",
    http: OkHttpClient = defaultHttp(),
): UpdateState {
    return try {
        val req = Request.Builder()
            .url("$apiBase/repos/$OWNER_REPO/releases/latest".trimEnd('/'))
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                return if (resp.code == 404) UpdateState.Failed("No releases published yet")
                else UpdateState.Failed("Update check failed (HTTP ${resp.code})")
            }
            val body = resp.body?.string().orEmpty()
            val rel = runCatching { json.decodeFromString<GhRelease>(body) }.getOrNull()
                ?: return UpdateState.Failed("Update check failed (bad response)")
            if (!isNewerThan(rel.tag, currentVersion)) return UpdateState.Current
            val apk = rel.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                ?: return UpdateState.Failed("Latest release has no APK yet")
            val version = parseVersionTag(rel.tag)?.let { (a, b, c) -> "$a.$b.$c" }
                ?: return UpdateState.Current
            UpdateState.Available(
                ReleaseInfo(version, rel.tag, apk.url, rel.body.orEmpty())
            )
        }
    } catch (e: java.net.UnknownHostException) {
        UpdateState.Failed("No connection — could not check for updates")
    } catch (e: java.net.SocketTimeoutException) {
        UpdateState.Failed("Update check timed out — try again")
    } catch (e: Exception) {
        UpdateState.Failed(e.message?.take(160) ?: "Update check failed")
    }
}
