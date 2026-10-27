package com.faznet.tv.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.faznet.tv.BuildConfig
import com.faznet.tv.R
import com.faznet.tv.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * Updates from the Faznet website (GitHub Pages): version.json says the newest version, the matching APK
 * (TV = armeabi-v7a, phone = arm64-v8a) is downloaded in the background and installed in place with
 * Android's package installer. The data (playlists, favourites, MAC + PIN) stays.
 *
 * Android only lets an app install another version of itself after one confirmation by the user:
 * on Android 12+ the update is silent once Faznet installed itself the first time; on older TVs
 * (Mi TV Stick: Android 11) the system shows one "Install" button.
 */
object Updater {

    const val VERSION_URL = "https://whatsthatbrother.github.io/faznet/version.json"
    private const val BASE = "https://whatsthatbrother.github.io/faznet/"

    class Info(val version: String, val code: Int, val url: String, val notes: String)

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("faznet_update", Context.MODE_PRIVATE)

    /** The APK this install came from: the 64-bit one only if the installed native code is 64-bit. */
    private fun isArm64(c: Context): Boolean =
        c.applicationInfo.nativeLibraryDir?.let { it.endsWith("arm64") || it.contains("arm64") } ?: false

    /** Newest version on the website, or null if this one is current / the site can't be reached. */
    suspend fun check(c: Context): Info? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(VERSION_URL + "?t=" + System.currentTimeMillis() / 600_000).build()
            Http.api.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val o = JSONObject(r.body?.string() ?: "{}")
                val code = o.optInt("code")
                if (code <= BuildConfig.VERSION_CODE) return@use null
                val path = if (isArm64(c)) o.optString("arm64") else o.optString("tv")
                if (path.isEmpty()) return@use null
                Info(o.optString("version"), code, if (path.startsWith("http")) path else BASE + path, o.optString("notes"))
            }
        }.getOrNull()
    }

    private fun apkFile(c: Context, code: Int) = File(c.cacheDir, "update-$code.apk")

    /** Downloads the APK (kept until installed, so a second start doesn't download it again). */
    suspend fun download(c: Context, info: Info): File? = withContext(Dispatchers.IO) {
        val f = apkFile(c, info.code)
        if (f.exists() && f.length() > 1_000_000) return@withContext f
        c.cacheDir.listFiles()?.filter { it.name.startsWith("update-") }?.forEach { it.delete() }
        val tmp = File(c.cacheDir, "update-${info.code}.part")
        runCatching {
            Http.client.newCall(Request.Builder().url(info.url).build()).execute().use { r ->
                if (!r.isSuccessful) return@runCatching null
                r.body?.byteStream()?.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
            }
            if (tmp.length() < 1_000_000 || !tmp.renameTo(f)) { tmp.delete(); null } else f
        }.getOrElse { tmp.delete(); null }
    }

    /** Android 8+: Faznet needs "install unknown apps" once. */
    fun canInstall(c: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || c.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(c: Context) {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { c.startActivity(i) }.onFailure {
            Toast.makeText(c, R.string.update_permission_manual, Toast.LENGTH_LONG).show()
        }
    }

    /** Installs the downloaded APK over this app (same signature, data kept). */
    fun install(c: Context, apk: File) {
        val pi = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(c.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            apk.inputStream().use { i -> s.openWrite("faznet.apk", 0, apk.length()).use { o -> i.copyTo(o, 256 * 1024); s.fsync(o) } }
            val intent = Intent(c, InstallReceiver::class.java).putExtra(EXTRA_APK, apk.absolutePath)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            s.commit(PendingIntent.getBroadcast(c, id, intent, flags).intentSender)
        }
        prefs(c).edit().putInt("installing", BuildConfig.VERSION_CODE).apply()
    }

    private const val EXTRA_APK = "faznet_apk"

    /** Opens the APK in Android's own installer (the classic "Install" screen). */
    fun openWithSystemInstaller(c: Context, apk: File): Boolean = runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(c, c.packageName + ".files", apk)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        c.startActivity(i)
        true
    }.getOrDefault(false)

    /** Result of the install: shows Android's confirmation screen when the system asks for it. */
    class InstallReceiver : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    runCatching { c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                PackageInstaller.STATUS_SUCCESS -> Unit // the app restarts as the new version
                PackageInstaller.STATUS_FAILURE_ABORTED -> Unit // the user said no
                else -> {
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                    ProblemLog.add(c, "Update", "install failed: $msg")
                    // Some phones (Play Protect verification, vendor installers) refuse the quiet install:
                    // hand the same file to Android's normal installer screen, which can ask the user instead.
                    val apk = intent.getStringExtra(EXTRA_APK)?.let { File(it) }
                    if (apk != null && apk.exists() && openWithSystemInstaller(c, apk)) return
                    Toast.makeText(c, c.getString(R.string.update_failed, msg), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
