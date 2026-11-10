package dev.syvyj.dropyk

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A newer release on GitHub: its version and the direct link to its APK. */
data class AppRelease(val version: String, val apkUrl: String)

/**
 * Updates from GitHub without a store, the same way Likhtar does it: every few hours ask
 * `releases/latest` (one request, nothing about the person), and if it's newer, offer it.
 * "Update" downloads the APK into the cache and opens the system installer — Android won't
 * install silently, and rightly so.
 */
// Holds only the application context, which lives as long as the process: no leak.
@SuppressLint("StaticFieldLeak")
object Update {
    private lateinit var context: Context
    private val prefs get() = context.getSharedPreferences("update", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _available = MutableStateFlow<AppRelease?>(null)
    /** A newer version, if there is one. */
    val available: StateFlow<AppRelease?> = _available.asStateFlow()

    private val _progress = MutableStateFlow<Float?>(null)
    /** APK download, 0–1; `null` when not downloading. */
    val progress: StateFlow<Float?> = _progress.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _awaitingPermission = MutableStateFlow(false)
    /** The "install unknown apps" screen is open; the update goes on by itself once it's allowed. */
    val awaitingPermission: StateFlow<Boolean> = _awaitingPermission.asStateFlow()
    @Volatile private var waiting: AppRelease? = null
    private val installing = AtomicBoolean(false)

    private val installed: String by lazy {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }

    fun init(app: Application) {
        context = app
        // What the last check found — right away, without the network.
        prefs.getString(KEY_RELEASE, null)?.let(::decode)?.takeIf { newer(it.version, installed) }?.let { _available.value = it }
        // Back from the system permission: carry on without a second "Update".
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                val release = waiting ?: return
                waiting = null
                _awaitingPermission.value = false
                // Back without allowing it: drop the "waiting for permission" line; "Update" asks again.
                if (canInstall()) scope.launch { install(release) }
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun canInstall() = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Ask GitHub if [CHECK_MS] has passed since the last answer, or right away with [force]. False if GitHub didn't answer. */
    suspend fun check(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(KEY_CHECKED, 0) < CHECK_MS) return@withContext true
        val result = runCatching {
            get(LATEST, TIMEOUT_MS) { c ->
                if (c.responseCode != 200) error("GitHub ${c.responseCode}") else parse(c.inputStream.bufferedReader().readText())
            }
        }.onFailure { Log.w(TAG, "update check", it) }
        // No answer (no network, rate limit) isn't "checked": the next start asks again.
        if (result.isFailure) return@withContext false
        val fresh = result.getOrNull()?.takeIf { newer(it.version, installed) }
        prefs.edit { putLong(KEY_CHECKED, now).putString(KEY_RELEASE, fresh?.let(::encode)) }
        _available.value = fresh
        true
    }

    /** Download the APK and open the installer; without the install permission, ask for it first. */
    fun start(release: AppRelease) {
        scope.launch { install(release) }
    }

    private fun install(release: AppRelease) {
        // A second press before the progress shows would start a second download into the same file.
        if (!installing.compareAndSet(false, true)) return
        try {
            download(release)
        } finally {
            installing.set(false)
        }
    }

    private fun download(release: AppRelease) {
        _error.value = null
        if (!canInstall()) {
            val ask = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Set before the screen opens: coming back from it is what reads them.
            waiting = release
            _awaitingPermission.value = true
            runCatching { context.startActivity(ask) }
                .onFailure { waiting = null; _awaitingPermission.value = false; _error.value = it.message ?: it.javaClass.simpleName }
            return
        }
        val apk = File(File(context.cacheDir, "updates").apply { mkdirs() }, APK_NAME)
        try {
            _progress.value = 0f
            get(release.apkUrl, DOWNLOAD_TIMEOUT_MS) { c ->
                if (c.responseCode != 200) error("GitHub ${c.responseCode}")
                val total = c.contentLengthLong.coerceAtLeast(1)
                c.inputStream.use { input ->
                    apk.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            _progress.value = (done.toFloat() / total).coerceAtMost(1f)
                        }
                    }
                }
            }
            // This app and really a package — otherwise the installer would show a puzzling error.
            val info = context.packageManager.getPackageArchiveInfo(apk.path, 0) ?: error("broken file")
            if (info.packageName != context.packageName) error("a different app")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
            context.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, Drop.APK)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            Log.w(TAG, "update", e)
            _error.value = e.message ?: e.javaClass.simpleName
        } finally {
            _progress.value = null
        }
    }

    private fun <T> get(url: String, readTimeout: Int, block: (HttpURLConnection) -> T): T {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = TIMEOUT_MS
        c.readTimeout = readTimeout
        c.setRequestProperty("Accept", "application/vnd.github+json")
        return try { block(c) } finally { c.disconnect() }
    }

    private fun encode(r: AppRelease) = JSONObject().put("v", r.version).put("u", r.apkUrl).toString()

    private fun decode(raw: String) = runCatching { JSONObject(raw).let { AppRelease(it.getString("v"), it.getString("u")) } }.getOrNull()

    private const val TAG = "DropykUpdate"
    private const val LATEST = "https://api.github.com/repos/Syvyj/dropyk-android/releases/latest"
    private const val APK_NAME = "dropyk.apk"
    private const val CHECK_MS = 6 * 60 * 60_000L
    private const val TIMEOUT_MS = 15_000
    private const val DOWNLOAD_TIMEOUT_MS = 120_000
    private const val KEY_CHECKED = "checked_at"
    private const val KEY_RELEASE = "release"

    /** GitHub's answer → a release; `null` without an APK among its assets. */
    internal fun parse(json: String): AppRelease? {
        val o = JSONObject(json)
        val assets = o.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length()).mapNotNull(assets::optJSONObject)
            .firstOrNull { it.optString("name") == APK_NAME }?.optString("browser_download_url") ?: return null
        return AppRelease(o.optString("tag_name").removePrefix("v"), apk)
    }

    /** "0.1.10" is newer than "0.1.9"; suffixes like "-beta1" are ignored. */
    internal fun newer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
