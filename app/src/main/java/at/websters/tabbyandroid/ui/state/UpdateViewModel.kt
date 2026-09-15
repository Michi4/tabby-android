package at.websters.tabbyandroid.ui.state

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import at.websters.tabbyandroid.BuildConfig
import at.websters.tabbyandroid.data.local.ProfileRepository
import at.websters.tabbyandroid.data.update.ReleaseInfo
import at.websters.tabbyandroid.data.update.UpdateState
import at.websters.tabbyandroid.data.update.checkForUpdate
import at.websters.tabbyandroid.data.update.isNewerThan
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * In-app updates from GitHub releases. Checks daily (silent) or on tap,
 * downloads directly inside the app with progress, then immediately opens
 * the system installer — no file hunting, no default-app hijack.
 * Auto-download is on by default: when an update is found it starts
 * downloading in the background and auto-opens the installer when done.
 */
class UpdateViewModel(app: Application) : AndroidViewModel(app) {
    sealed interface Ui {
        data object Idle : Ui
        data object Checking : Ui
        data object UpToDate : Ui
        data class Available(val info: ReleaseInfo) : Ui
        data class Failed(val message: String) : Ui
        data class Downloading(val info: ReleaseInfo, val progress: Float) : Ui
        data class ReadyToInstall(val info: ReleaseInfo, val file: File) : Ui
        data class DownloadFailed(val message: String) : Ui
    }

    private val repo = ProfileRepository(app)

    private val _ui = MutableStateFlow<Ui>(Ui.Idle)
    val ui: StateFlow<Ui> = _ui.stateIn(viewModelScope, SharingStarted.Eagerly, Ui.Idle)

    val autoDownload: StateFlow<Boolean> = repo.updateAutoDownload
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setAutoDownload(enabled: Boolean) {
        viewModelScope.launch { runCatching { repo.setUpdateAutoDownload(enabled) } }
    }

    @Volatile var pendingApk: File? = null
        private set

    private var downloadJob: Job? = null

    init {
        // Surface a cached newer release; the daily check is owned by TabbyApp
        // (single trigger — no double check at launch). Downloads only start
        // from check()/explicit tap, and the installer ONLY ever opens from an
        // explicit user tap (never automatically — no popups over your work).
        viewModelScope.launch {
            val (_, cached) = repo.updateCheck.first()
            if (cached != null && isNewerThan(cached.tag, BuildConfig.VERSION_NAME)) {
                if (isApkCached(cached)) {
                    pendingApk = cachedApkFile(cached)
                    _ui.value = Ui.ReadyToInstall(cached, pendingApk!!)
                } else {
                    _ui.value = Ui.Available(cached)
                    if (repo.updateAutoDownload.first()) startDownload(cached)
                }
            }
        }
    }

    fun check(manual: Boolean = false) {
        viewModelScope.launch {
            if (_ui.value is Ui.Checking || _ui.value is Ui.Downloading) return@launch
            val (lastAt, _) = repo.updateCheck.first()
            if (!manual && System.currentTimeMillis() - lastAt < 24L * 3600 * 1000L) return@launch
            _ui.value = Ui.Checking
            when (val r = checkForUpdate(BuildConfig.VERSION_NAME)) {
                is UpdateState.Available -> {
                    repo.saveUpdateCheck(System.currentTimeMillis(), r.info)
                    _ui.value = Ui.Available(r.info)
                    if (repo.updateAutoDownload.first()) {
                        // tiny delay so card is visible before progress starts
                        kotlinx.coroutines.delay(300)
                        startDownload(r.info)
                    }
                }
                is UpdateState.Current -> {
                    repo.saveUpdateCheck(System.currentTimeMillis(), null)
                    _ui.value = Ui.UpToDate
                }
                is UpdateState.Failed -> {
                    if (manual) _ui.value = Ui.Failed(r.message)
                    else if (_ui.value is Ui.Checking) _ui.value = Ui.Idle
                }
            }
        }
    }

    fun dismissMessage() {
        if (_ui.value is Ui.Failed || _ui.value is Ui.DownloadFailed) _ui.value = Ui.Idle
    }

    private fun apkDir(): File {
        val ctx = getApplication<Application>()
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
        dir.mkdirs()
        return dir
    }

    private fun cachedApkFile(info: ReleaseInfo): File {
        val safeTag = info.tag.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(apkDir(), "tabby-android-${safeTag}.apk")
    }

    private fun isApkCached(info: ReleaseInfo): Boolean {
        val f = cachedApkFile(info)
        return f.exists() && f.length() > 1024 * 1024
    }

    fun startDownload(info: ReleaseInfo) {
        // Re-entry guard: never stack/cancel-restart an active download.
        if (_ui.value is Ui.Downloading) return
        downloadJob?.cancel()
        val safeTag = info.tag.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dir = apkDir()
        // clean old APKs (keep only this one) to avoid confusion
        dir.listFiles()?.filter { it.name.startsWith("tabby-android-") && it.name != "tabby-android-${safeTag}.apk" }?.forEach { runCatching { it.delete() } }
        val file = File(dir, "tabby-android-${safeTag}.apk")
        pendingApk = file
        if (isApkCached(info)) {
            _ui.value = Ui.ReadyToInstall(info, file)
            // Deliberately NO auto-install: the system installer only ever
            // opens from an explicit user tap, never as a surprise popup.
            return
        }
        if (file.exists()) file.delete()
        _ui.value = Ui.Downloading(info, 0f)
        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .callTimeout(5, TimeUnit.MINUTES)
                    .followRedirects(true)
                    .build()
                val req = Request.Builder()
                    .url(info.apkUrl)
                    .header("Accept", "application/octet-stream")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IllegalStateException("Empty body")
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        file.outputStream().use { output ->
                            val buf = ByteArray(32 * 1024)
                            var downloaded = 0L
                            var lastProgress = 0f
                            var lastEmit = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n == -1) break
                                output.write(buf, 0, n)
                                downloaded += n
                                if (total > 0) {
                                    val prog = (downloaded.toFloat() / total).coerceIn(0f, 1f)
                                    val now = System.currentTimeMillis()
                                    if (prog - lastProgress > 0.02f || now - lastEmit > 250) {
                                        lastProgress = prog
                                        lastEmit = now
                                        withContext(Dispatchers.Main) {
                                            if (_ui.value is Ui.Downloading) _ui.value = Ui.Downloading(info, prog)
                                        }
                                    }
                                }
                            }
                            output.fd.sync()
                        }
                    }
                    if (file.length() < 1024 * 1024) throw IllegalStateException("Download incomplete (${file.length()} bytes)")
                }
                withContext(Dispatchers.Main) {
                    _ui.value = Ui.ReadyToInstall(info, file)
                    // No auto-install here either — user taps Install when ready.
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                file.delete()
                withContext(Dispatchers.Main) {
                    _ui.value = Ui.DownloadFailed(e.message?.take(140) ?: "Download failed")
                }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        _ui.value = Ui.Idle
    }

    /**
     * Installs the APK. Uses PackageInstaller when possible (bypasses default-app hijack),
     * falls back to FileProvider + ACTION_VIEW with explicit package targeting.
     * Returns false when system blocked it (unknown-sources off → user sent to settings).
     */
    fun installApk(file: File? = pendingApk): Boolean {
        val ctx = getApplication<Application>()
        val f = file?.takeIf { it.exists() } ?: pendingApk?.takeIf { it.exists() } ?: return false
        pendingApk = f
        try {
            if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
                ctx.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${ctx.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return false
            }
            // Try PackageInstaller first (most reliable, bypasses default handler)
            if (tryPackageInstaller(f)) return true
            // Fallback: FileProvider + VIEW intent
            return installViaViewIntent(f)
        } catch (_: Exception) {
            return installViaViewIntent(f)
        }
    }

    private fun tryPackageInstaller(file: File): Boolean {
        return try {
            val ctx = getApplication<Application>()
            val pm = ctx.packageManager
            if (Build.VERSION.SDK_INT < 21) return false
            val installer = pm.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)
            try {
                session.openWrite("apk", 0, file.length()).use { out ->
                    file.inputStream().use { input -> input.copyTo(out) }
                    session.fsync(out)
                }
                val pi = android.app.PendingIntent.getBroadcast(
                    ctx, sessionId, Intent("tabby.install.status"),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                session.commit(pi.intentSender)
                session.close()
                true
            } catch (e: Exception) {
                runCatching { session.close() }
                runCatching { installer.abandonSession(sessionId) }
                false
            }
        } catch (_: Exception) { false }
    }

    private fun installViaViewIntent(file: File): Boolean {
        return try {
            val ctx = getApplication<Application>()
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val baseIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val pm = ctx.packageManager
            val resolvers = pm.queryIntentActivities(baseIntent, 0)
            // Prefer system package installer to avoid default-app hijack
            val installerPkg = resolvers.firstOrNull {
                it.activityInfo.packageName.contains("packageinstaller", ignoreCase = true)
            }?.activityInfo?.packageName
            if (installerPkg != null) baseIntent.setPackage(installerPkg)
            // Grant to all resolvers
            for (info in resolvers) {
                ctx.grantUriPermission(info.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                ctx.startActivity(baseIntent)
                true
            } catch (_: Exception) {
                // Last resort: chooser
                val chooser = Intent.createChooser(baseIntent, "Install update").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(chooser)
                true
            }
        } catch (_: Exception) { false }
    }

    // Legacy DownloadManager hook — kept for broadcast compat but primary path is direct OkHttp
    @Volatile var pendingDownloadId: Long = -1L
    fun onDownloadComplete(downloadId: Long): Boolean {
        if (downloadId != pendingDownloadId) return false
        return installApk()
    }

    sealed interface DownloadStart {
        data class Started(val id: Long) : DownloadStart
        data object AlreadyHave : DownloadStart
        data object Unavailable : DownloadStart
    }

    // Back-compat for older UI calls
    fun startDownloadLegacy(info: ReleaseInfo): DownloadStart = DownloadStart.Unavailable
}
