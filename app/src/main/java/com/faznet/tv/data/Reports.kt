package com.faznet.tv.data

import android.content.Context
import android.os.Build
import com.faznet.tv.BuildConfig
import com.faznet.tv.net.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
        append("Engine ExoPlayer · FFmpeg ${repo.useFfmpeg} · SW-SD ${repo.softwareSd} · playlists ${repo.playlists().size}\n")
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

    /** How the app's process ended, as Android 11+ keeps it ([android.app.ApplicationExitInfo]). */
    class Exit(val at: Long, val reason: Int, val description: String, val foreground: Boolean, val pssKb: Long, val trace: String = "")

    /**
     * The endings the crash file can't catch: a crash in native code (an image or video decoder writing over memory),
     * "not responding", and running out of memory while on the screen. A crash in Kotlin/Java comes with crash.txt.
     */
    internal fun exitText(exits: List<Exit>, since: Long): String? {
        val fmt = java.text.SimpleDateFormat("dd.MM HH:mm:ss", java.util.Locale.US)
        val lines = exits.filter { it.at > since }.sortedBy { it.at }.mapNotNull { e ->
            val what = when {
                e.reason == REASON_CRASH_NATIVE -> "native crash"
                e.reason == REASON_ANR -> "not responding (ANR)"
                e.reason == REASON_LOW_MEMORY && e.foreground -> "killed for memory while on screen"
                else -> return@mapNotNull null
            }
            buildString {
                append("${fmt.format(java.util.Date(e.at))}  $what: ${e.description.take(300)}  pss=${e.pssKb / 1024} MB")
                if (e.trace.isNotBlank()) append('\n').append(e.trace.take(4_000))
            }
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    /** Only the main thread of an ANR trace: what held the screen. */
    internal fun mainThread(trace: String): String {
        val start = trace.indexOf("\"main\"")
        if (start < 0) return ""
        val end = trace.indexOf("\n\n", start).let { if (it < 0) trace.length else it }
        return trace.substring(start, end).lineSequence().take(40).joinToString("\n")
    }

    /** At start: the ways the app ended since the last look that crash.txt can't tell (Android 11+). */
    suspend fun sendExits(c: Context, repo: Repository) {
        if (Build.VERSION.SDK_INT < 30) return
        val sp = c.applicationContext.getSharedPreferences("reports", Context.MODE_PRIVATE)
        // The first time, the last day: what happened just before this version came is worth knowing too.
        val since = sp.getLong("exit_seen", 0L).takeIf { it > 0 } ?: (System.currentTimeMillis() - 86_400_000L)
        val exits = withContext(Dispatchers.IO) {
            runCatching {
                val am = c.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                am.getHistoricalProcessExitReasons(c.packageName, 0, 10).map { e ->
                    val trace = if (e.reason == REASON_ANR) {
                        runCatching { e.traceInputStream?.bufferedReader()?.use { mainThread(it.readText()) } }.getOrNull().orEmpty()
                    } else ""
                    Exit(
                        e.timestamp, e.reason, e.description.orEmpty(),
                        e.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE, e.pss, trace,
                    )
                }
            }.getOrDefault(emptyList())
        }
        val newest = exits.maxOfOrNull { it.at } ?: return
        val text = exitText(exits, since)
        val sent = text == null || !repo.autoReports || post(repo, "exit", "How the app ended:\n$text\n\n" + summary(c, repo))
        if (sent && newest > since) sp.edit().putLong("exit_seen", newest).apply()
    }

    // ApplicationExitInfo's numbers, here so the text can be tested on any API level.
    internal const val REASON_LOW_MEMORY = 3
    internal const val REASON_CRASH_NATIVE = 5
    internal const val REASON_ANR = 6

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var pending: Job? = null

    /**
     * A new line in the problem log: send the log to the developer by itself, without asking
     * (only when automatic reports are on). Problems that come in a burst are sent together:
     * one minute after the last one, and at most every 10 minutes.
     */
    fun problemAdded(c: Context) {
        val app = c.applicationContext
        val repo = Repository.get(app)
        if (!repo.autoReports) return
        pending?.cancel()
        pending = scope.launch {
            delay(60_000)
            val sp = app.getSharedPreferences("reports", Context.MODE_PRIVATE)
            val wait = 10 * 60_000L - (System.currentTimeMillis() - sp.getLong("auto_at", 0))
            if (wait > 0) delay(wait)
            sendNewProblems(app, repo)
        }
    }

    /** Sends the problem log if it has entries that were not sent yet (at app start and after new problems). */
    suspend fun sendNewProblems(c: Context, repo: Repository) {
        if (!repo.autoReports) return
        val sp = c.applicationContext.getSharedPreferences("reports", Context.MODE_PRIVATE)
        val top = withContext(Dispatchers.IO) { ProblemLog.read(c).firstOrNull() } ?: return
        if (top == sp.getString("auto_top", null)) return
        if (post(repo, "auto", summary(c, repo))) {
            sp.edit().putLong("auto_at", System.currentTimeMillis()).putString("auto_top", top).apply()
        }
    }
}
