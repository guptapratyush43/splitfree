package com.splitfree.update

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Checks GitHub Releases for a newer version and installs it in place.
 * Free forever: it reads GitHub's public release info, no account or key.
 *
 * It never nags: at most one check a day, and a version the user ignored is
 * never offered again. Only a release newer than that one brings the pop-up back.
 */
object UpdateManager {
    private const val LATEST = "https://api.github.com/repos/guptapratyush43/splitfree/releases/latest"
    private const val DAY = 20L * 60 * 60 * 1000 // "about once a day", forgiving of odd opening times

    data class Release(val version: String, val notes: String, val apkUrl: String, val apkSize: Long, val sha256: String?)

    sealed interface Download {
        data object Idle : Download
        data class Running(val progress: Float?) : Download
        data class Ready(val file: File) : Download
        data class Failed(val message: String) : Download
    }

    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences

    private val _offer = MutableStateFlow<Release?>(null)
    /** The release to show in the pop-up, or null. */
    val offer: StateFlow<Release?> = _offer.asStateFlow()
    private val _verifying = MutableStateFlow(false)
    /** True while the pop-up double-checks with GitHub that it shows the newest version. */
    val verifying: StateFlow<Boolean> = _verifying.asStateFlow()
    private val _download = MutableStateFlow<Download>(Download.Idle)
    val download: StateFlow<Download> = _download.asStateFlow()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        app = context.applicationContext
        prefs = app.getSharedPreferences("updates", Context.MODE_PRIVATE)
    }

    val currentVersion: String
        get() = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "0"

    /** True when [a] is a higher version than [b] ("2.10" > "2.9" is handled too). */
    fun isNewer(a: String, b: String): Boolean {
        val x = a.trimStart('v', 'V').split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.trimStart('v', 'V').split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /**
     * The daily background check (WorkManager): quietly looks at GitHub and
     * remembers a newer release. Nothing is shown until the app is next opened.
     */
    suspend fun checkInBackground() {
        val release = fetch()
        prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
        if (isNewer(release.version, currentVersion)) remember(release)
    }

    /**
     * On app open: offer a release found earlier (or found now, if a day has
     * passed). A version the user ignored is never offered again; only a newer one is.
     */
    suspend fun checkOnLaunch() {
        val ignored = prefs.getString("ignored", null)
        fun offerable(r: Release) = isNewer(r.version, currentVersion) && (ignored == null || isNewer(r.version, ignored))
        // Show what the last check found at once, with a spinner, while asking GitHub for the newest.
        val cached = remembered()?.takeIf { offerable(it) }
        val started = System.currentTimeMillis()
        if (cached != null) { _verifying.value = true; _offer.value = cached }
        try {
            val fresh = runCatching { fetch() }.getOrNull()
            if (fresh != null) {
                prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
                if (isNewer(fresh.version, currentVersion)) remember(fresh)
                // Keep the spinner up for at least a second so the switch never flickers.
                if (cached != null) kotlinx.coroutines.delay((1000 - (System.currentTimeMillis() - started)).coerceAtLeast(0))
                if (offerable(fresh)) _offer.value = fresh
                else if (cached != null && _download.value is Download.Idle) _offer.value = null
            }
        } finally { _verifying.value = false }
    }

    private fun remember(r: Release) = prefs.edit()
        .putString("pending_version", r.version).putString("pending_notes", r.notes).putString("pending_url", r.apkUrl)
        .putLong("pending_size", r.apkSize).putString("pending_sha", r.sha256).apply()

    private fun remembered(): Release? {
        val v = prefs.getString("pending_version", null) ?: return null
        return Release(v, prefs.getString("pending_notes", "").orEmpty(), prefs.getString("pending_url", "").orEmpty(),
            prefs.getLong("pending_size", 0), prefs.getString("pending_sha", null))
    }

    /** Settings > Check for updates: asks right away, and offers even an ignored version. */
    suspend fun checkNow(): Release? {
        val release = fetch()
        prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
        return release.takeIf { isNewer(it.version, currentVersion) }?.also { remember(it); _offer.value = it }
    }

    /** "Ignore": this version is never offered again; the next one will be. */
    fun ignore(release: Release) {
        prefs.edit().putString("ignored", release.version).apply()
        close()
    }

    fun close() {
        _offer.value = null
        if (_download.value !is Download.Running) _download.value = Download.Idle
    }

    private suspend fun fetch(): Release = withContext(Dispatchers.IO) {
        val c = (URL(LATEST).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "SplitFree-Android")
            // Always the live answer, never a cached one that could point at an older release.
            useCaches = false
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            if (c.responseCode != 200) throw IOException("GitHub said ${c.responseCode}")
            val json = JSONObject(c.inputStream.bufferedReader().readText())
            val assets = json.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk", ignoreCase = true) }
                ?: throw IOException("That release has no APK attached.")
            Release(
                version = json.getString("tag_name").trimStart('v', 'V'),
                notes = tidy(json.optString("body")),
                apkUrl = apk.getString("browser_download_url"),
                apkSize = apk.optLong("size"),
                sha256 = apk.optString("digest").removePrefix("sha256:").takeIf { it.length == 64 }
            )
        } finally {
            c.disconnect()
        }
    }

    /** Release notes are Markdown on GitHub; the pop-up shows them as plain, tidy text. */
    private fun tidy(md: String): String = md
        .replace(Regex("""\[([^\]]+)]\([^)]+\)"""), "$1")
        .replace("**", "")
        .replace("`", "")
        .lines()
        .map { it.trimEnd() }
        .map { if (it.trimStart().startsWith("- ")) "• " + it.trimStart().removePrefix("- ") else it.removePrefix("## ").removePrefix("# ") }
        .joinToString("\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    /** Downloads the APK with progress, then checks it really is our app, signed by us. */
    suspend fun startDownload(release: Release) {
        if (_download.value is Download.Running) return
        _download.value = Download.Running(0f)
        try {
            val file = withContext(Dispatchers.IO) {
                val dir = File(app.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val out = File(dir, "Split-Free-${release.version}.apk")
                val c = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    setRequestProperty("User-Agent", "SplitFree-Android")
            // Always the live answer, never a cached one that could point at an older release.
            useCaches = false
            setRequestProperty("Cache-Control", "no-cache")
                }
                try {
                    if (c.responseCode != 200) throw IOException("Download failed (${c.responseCode}).")
                    val total = c.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
                    val digest = MessageDigest.getInstance("SHA-256")
                    c.inputStream.use { input ->
                        out.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                digest.update(buf, 0, n)
                                done += n
                                _download.value = Download.Running(if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null)
                            }
                        }
                    }
                    val hex = digest.digest().joinToString("") { "%02x".format(it) }
                    if (release.sha256 != null && !hex.equals(release.sha256, ignoreCase = true)) {
                        out.delete(); throw IOException("The download got damaged. Try again.")
                    }
                } finally {
                    c.disconnect()
                }
                if (!signedLikeUs(out)) { out.delete(); throw IOException("That file isn't signed like this app, so it wasn't installed.") }
                out
            }
            _download.value = Download.Ready(file)
        } catch (e: Exception) {
            _download.value = Download.Failed(
                if (e is java.net.UnknownHostException || e is java.net.SocketTimeoutException) "No internet right now. Try again in a bit."
                else e.message ?: "Download failed."
            )
        }
    }

    /**
     * Opens Android's installer. The first time, Android wants the user to allow
     * this app to install updates; returns false after sending them there.
     */
    fun install(context: Context, file: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }

    /** Same package, same signing key and a higher version than what's installed. */
    private fun signedLikeUs(apk: File): Boolean = runCatching {
        val pm = app.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val theirs = pm.getPackageArchiveInfo(apk.path, flags) ?: return false
        val ours = pm.getPackageInfo(app.packageName, flags)
        theirs.packageName == app.packageName && certs(theirs) == certs(ours) && certs(ours).isNotEmpty()
    }.getOrDefault(false)

    private fun certs(info: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners?.toList().orEmpty()
        else @Suppress("DEPRECATION") info.signatures?.toList().orEmpty()
        return sigs.map { sig -> MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }
}

/** Runs once a day in the background; any failure just waits for tomorrow. */
class UpdateWorker(context: Context, params: androidx.work.WorkerParameters) : androidx.work.CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        UpdateManager.init(applicationContext)
        runCatching { UpdateManager.checkInBackground() }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "update-check", androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                androidx.work.PeriodicWorkRequestBuilder<UpdateWorker>(1, java.util.concurrent.TimeUnit.DAYS)
                    .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                    .build()
            )
        }
    }
}
