package at.websters.tabbyandroid.ui.state

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * In-app updates from GitHub releases. Auto-checks at most once a day
 * (manual taps always check); downloads via DownloadManager into the app's
 * own directory and installs through FileProvider — no store needed.
 */
class UpdateViewModel(app: Application) : AndroidViewModel(app) {
    sealed interface Ui {
        data object Idle : Ui
        data object Checking : Ui
        data object UpToDate : Ui
        data class Available(val info: ReleaseInfo) : Ui
        data class Failed(val message: String) : Ui
        data object Downloading : Ui
    }

    private val repo = ProfileRepository(app)

    private val _ui = MutableStateFlow<Ui>(Ui.Idle)
    val ui: StateFlow<Ui> = _ui.stateIn(viewModelScope, SharingStarted.Eagerly, Ui.Idle)

    /** Last completed download (id + file), for the completion receiver. */
    @Volatile var pendingDownloadId: Long = -1L
        private set
    @Volatile var pendingApk: File? = null
        private set

    init {
        viewModelScope.launch {
            // instant banner from the last known release, then a fresh check
            val (_, cached) = repo.updateCheck.first()
            if (cached != null && isNewerThan(cached.tag, BuildConfig.VERSION_NAME)) {
                _ui.value = Ui.Available(cached)
            }
            check(manual = false)
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
                }
                is UpdateState.Current -> {
                    repo.saveUpdateCheck(System.currentTimeMillis(), null)
                    _ui.value = Ui.UpToDate
                }
                is UpdateState.Failed -> {
                    // auto-checks stay silent; manual taps see the reason
                    if (manual) _ui.value = Ui.Failed(r.message)
                    else if (_ui.value is Ui.Checking) _ui.value = Ui.Idle
                }
            }
        }
    }

    fun dismissMessage() {
        if (_ui.value is Ui.Failed) _ui.value = Ui.Idle
    }

    /**
     * Starts (or reuses, if the file is already there) the APK download.
     */
    sealed interface DownloadStart {
        data class Started(val id: Long) : DownloadStart
        data object AlreadyHave : DownloadStart
        data object Unavailable : DownloadStart
    }

    fun startDownload(info: ReleaseInfo): DownloadStart {
        return try {
            val ctx = getApplication<Application>()
            val safeTag = info.tag.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val file = File(
                ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                "tabby-android-${safeTag}.apk",
            )
            pendingApk = file
            if (file.exists() && file.length() > 0) {
                _ui.value = Ui.Downloading
                return DownloadStart.AlreadyHave
            }
            if (file.exists()) file.delete()
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val req = DownloadManager.Request(Uri.parse(info.apkUrl))
                .setTitle("Tabby ${safeTag}")
                .setDescription("Downloading update")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setDestinationUri(Uri.fromFile(file))
                .setAllowedOverMetered(true)
            pendingApk = file
            _ui.value = Ui.Downloading
            val id = dm.enqueue(req)
            pendingDownloadId = id
            DownloadStart.Started(id)
        } catch (_: Exception) {
            DownloadStart.Unavailable
        }
    }

    /**
     * Installs the downloaded APK. Returns false when the system blocked it
     * (unknown-sources off → user was sent to enable it; tap Update again).
     */
    fun installApk(): Boolean {
        val ctx = getApplication<Application>()
        val file = pendingApk?.takeIf { it.exists() } ?: return false
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
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return true
        } catch (_: Exception) {
            return false
        }
    }

    fun onDownloadComplete(downloadId: Long): Boolean {
        if (downloadId != pendingDownloadId) return false
        return installApk()
    }
}
