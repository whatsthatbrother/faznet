package com.faznet.tv.data

import android.content.Context
import android.os.Build
import com.faznet.tv.BuildConfig
import com.faznet.tv.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Problem reports to the Faznet server (seen by the developer on the server's report page).
 * They contain the app version, device, settings, the problem log and crashes — never playlist addresses.
 */
object Reports {

    private fun crashFile(c: Context) = File(c.applicationContext.filesDir, "crash.txt")

    /** Called from the app's uncaught-exception handler: keep the crash for the next start. */
    fun saveCrash(c: Context, t: Throwable) {
        runCatching {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            crashFile(c).writeText(sw.toString().take(15_000))
        }
    }

    private fun summary(c: Context, repo: Repository): String = buildString {
        append("Faznet ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
        append("Device ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.SUPPORTED_ABIS.joinToString()}\n")
        append("Engine ${if (repo.useVlc) "VLC" else "ExoPlayer"} · FFmpeg ${repo.useFfmpeg} · SW-SD ${repo.softwareSd} · playlists ${repo.playlists().size}\n")
        val rt = Runtime.getRuntime()
        append("Memory max ${rt.maxMemory() / 1048576} MB\n")
        val log = ProblemLog.read(c)
        if (log.isNotEmpty()) {
            append("\nProblem log (newest first):\n")
            log.take(50).forEach { append(it).append('\n') }
        }
    }

    private suspend fun post(repo: Repository, kind: String, text: String): Boolean = withContext(Dispatchers.IO) {
        val server = repo.serverUrl
        if (server.isEmpty()) return@withContext false
        runCatching {
            val payload = JSONObject()
                .put("kind", kind)
                .put("mac", repo.deviceMac)
                .put("version", BuildConfig.VERSION_NAME)
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}")
                .put("text", text.take(19_000))
            val req = Request.Builder().url("$server/api/report")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            Http.api.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /** "Send to the developer" from the problem log. */
    suspend fun sendManual(c: Context, repo: Repository, note: String = ""): Boolean =
        post(repo, "manual", (if (note.isNotBlank()) "Note: $note\n\n" else "") + summary(c, repo))

    /** At start: send a crash saved last time (if reports are allowed), then forget it. */
    suspend fun sendPendingCrash(c: Context, repo: Repository) {
        val f = crashFile(c)
        if (!f.exists()) return
        if (!repo.autoReports) { f.delete(); return }
        val crash = runCatching { f.readText() }.getOrDefault("")
        if (post(repo, "crash", "Crash:\n$crash\n\n" + summary(c, repo))) f.delete()
    }
}
