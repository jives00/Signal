package com.signal.tv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.signal.update.GitHubReleases
import com.signal.update.UpdateState
import com.signal.update.Updater
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The auto-updater (PLAN §4.3.15), the same flow as Pulse, Quest, Travel and Weather: compare this
 * build's apk-* tag with the newest GitHub Release, and on Install download the APK and hand it
 * to Android's package installer. The repo is public, so no token is needed.
 */
class AndroidUpdater(
    private val context: Context,
    private val http: HttpClient,
    private val currentTag: String,
) : Updater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.None)
    override val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var release: GitHubReleases.Release? = null
    private val prefs = context.getSharedPreferences("updater", Context.MODE_PRIVATE)

    override suspend fun check() {
        if (_state.value is UpdateState.Downloading) return
        runCatching {
            val response = withContext(Dispatchers.IO) { http.get(GitHubReleases.LATEST_URL) }
            if (!response.status.isSuccess()) return
            val latest = GitHubReleases.parseLatest(response.bodyAsText()) ?: return
            release = latest
            val dismissed = prefs.getString(KEY_DISMISSED, null)
            _state.value = if (GitHubReleases.isNewer(currentTag, latest.tag) && latest.tag != dismissed) {
                UpdateState.Available(latest.tag, latest.body)
            } else {
                UpdateState.None
            }
        }
    }

    override fun install() {
        val r = release ?: return
        if (!context.packageManager.canRequestPackageInstalls()) {
            // First time: Android asks the user to allow Signal to install apps.
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            _state.value = UpdateState.Failed(r.tag, "Allow Signal to install apps, then press OK again")
            return
        }
        scope.launch {
            runCatching {
                _state.value = UpdateState.Downloading(r.tag, 0f)
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                val file = File(dir, "signal.apk").apply { delete() }
                http.prepareGet(r.apkUrl).execute { response ->
                    if (!response.status.isSuccess()) error("HTTP ${response.status.value}")
                    val total = response.contentLength() ?: -1L
                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    file.outputStream().use { out ->
                        while (true) {
                            val n = channel.readAvailable(buffer)
                            if (n <= 0) { if (channel.isClosedForRead) break else continue }
                            out.write(buffer, 0, n)
                            written += n
                            if (total > 0) _state.value = UpdateState.Downloading(r.tag, written.toFloat() / total)
                        }
                    }
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
                context.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                _state.value = UpdateState.Available(r.tag, r.body)
            }.onFailure { e -> _state.value = UpdateState.Failed(r.tag, e.message ?: "Download failed") }
        }
    }

    override fun later() {
        release?.let { prefs.edit().putString(KEY_DISMISSED, it.tag).apply() }
        _state.value = UpdateState.None
    }

    private companion object {
        const val KEY_DISMISSED = "dismissed_tag"
    }
}
