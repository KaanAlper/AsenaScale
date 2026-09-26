package dev.asenascale.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import dev.asenascale.App
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates from the GitHub releases: checks the latest release, downloads
 * the APK for this phone's CPU and opens Android's install screen. Android
 * always asks the user to confirm; the first time it also asks to allow
 * installs from this app.
 */
object Updater {
    private const val REPO = "KaanAlper/AsenaScale"
    private const val EVERY_MS = 6 * 3600_000L

    sealed interface Status {
        data object Idle : Status
        data object Checking : Status
        data object UpToDate : Status
        data class Downloading(val version: String, val fraction: Float) : Status
        data class Ready(val version: String, val file: File) : Status
        data class Failed(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status

    val current: String by lazy {
        App.instance.packageManager.getPackageInfo(App.instance.packageName, 0).versionName ?: "0"
    }

    /** Branch and local builds don't replace themselves unasked. */
    private val autoAllowed get() = !current.contains('-')

    @Volatile private var running = false

    /**
     * Checks (at most every 6 h unless [force]) and downloads a newer APK.
     * Returns true when an update is ready to install. Blocking: call off
     * the main thread.
     */
    fun checkAndDownload(context: Context, force: Boolean): Boolean {
        if (running) return false
        if (!force && !autoAllowed) return false
        val prefs = context.getSharedPreferences("update", Context.MODE_PRIVATE)
        if (!force && System.currentTimeMillis() - prefs.getLong("last", 0) < EVERY_MS) {
            return _status.value is Status.Ready
        }
        running = true
        try {
            _status.value = Status.Checking
            val release = JSONObject(get("https://api.github.com/repos/$REPO/releases/latest"))
            prefs.edit().putLong("last", System.currentTimeMillis()).apply()
            val version = release.getString("tag_name").removePrefix("v")
            if (!newer(version, current)) {
                _status.value = Status.UpToDate
                return false
            }
            val assets = release.getJSONArray("assets")
            val byName = (0 until assets.length()).associate {
                val a = assets.getJSONObject(it)
                a.getString("name") to a.getString("browser_download_url")
            }
            val abi = Build.SUPPORTED_ABIS.firstOrNull { byName.containsKey("AsenaScale-Mobile-$it.apk") }
            val url = byName["AsenaScale-Mobile-$abi.apk"] ?: byName["AsenaScale-Mobile-universal.apk"]
                ?: error("no APK in release $version")
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "AsenaScale-Mobile-$version.apk")
            download(url, file) { _status.value = Status.Downloading(version, it) }
            _status.value = Status.Ready(version, file)
            return true
        } catch (e: Exception) {
            Log.w("update", "update failed", e)
            _status.value = Status.Failed(e.message ?: e.toString())
            return false
        } finally {
            running = false
        }
    }

    /** Opens Android's installer for the downloaded APK (or the permission page first). */
    fun install(context: Context) {
        val ready = _status.value as? Status.Ready ?: return
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", ready.file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Offer the install screen by itself only once per version. */
    fun shouldAutoOffer(context: Context): Boolean {
        val ready = _status.value as? Status.Ready ?: return false
        val prefs = context.getSharedPreferences("update", Context.MODE_PRIVATE)
        if (prefs.getString("offered", null) == ready.version) return false
        prefs.edit().putString("offered", ready.version).apply()
        return true
    }

    /** "0.5.10" > "0.5.9"; a pre-release ("0.5.2-nightly") is older than "0.5.2". */
    fun newer(candidate: String, than: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(than)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return than.contains('-') && !candidate.contains('-')
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AsenaScale-Mobile")
        }

    private fun get(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    private fun download(url: String, to: File, progress: (Float) -> Unit) {
        val c = open(url)
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            var done = 0L
            var last = 0L
            c.inputStream.use { input ->
                to.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0 && done - last > 256 * 1024) {
                            last = done
                            progress(done.toFloat() / total)
                        }
                    }
                }
            }
            if (total > 0 && done != total) error("download cut short")
        } finally {
            c.disconnect()
        }
    }
}
