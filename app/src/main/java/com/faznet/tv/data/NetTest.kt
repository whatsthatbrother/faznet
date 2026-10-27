package com.faznet.tv.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.SystemClock
import com.faznet.tv.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Internet test for IPTV: how the connection behaves towards the user's own IPTV server (delay, jitter,
 * lost requests) and how much the line can download (speed), plus Wi-Fi signal. Nothing is sent anywhere.
 */
object NetTest {

    enum class Link { WIFI, ETHERNET, MOBILE, OTHER, NONE }

    class Connection(val link: Link, val signalPct: Int?)

    class Ping(val avgMs: Int?, val jitterMs: Int?, val lossPct: Int)

    enum class Verdict { STABLE, OK, UNSTABLE, OFFLINE }

    private const val SPEED_URL = "https://whatsthatbrother.github.io/faznet/download/Faznet-arm64.apk"
    private const val FALLBACK_PING = "https://whatsthatbrother.github.io/faznet/version.json"

    private val client by lazy {
        Http.client.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    fun connection(c: Context): Connection {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return Connection(Link.NONE, null)
        val link = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Link.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Link.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Link.MOBILE
            else -> Link.OTHER
        }
        val signal = if (link != Link.WIFI) null else runCatching {
            @Suppress("DEPRECATION")
            val rssi = (c.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo.rssi
            if (rssi <= -127 || rssi >= 0) null else (2 * (rssi + 100)).coerceIn(0, 100)
        }.getOrNull()
        return Connection(link, signal)
    }

    /** The server the user's channels come from (only its address is contacted; no login is sent). */
    fun iptvHost(repo: Repository): String? {
        val p = repo.activePlaylist() ?: return null
        val src = XtreamAccount.from(p)?.server ?: p.source
        val u = src.toHttpUrlOrNull() ?: return null
        return u.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
    }

    /** [count] small requests one after another: delay, jitter and how many got no answer. */
    suspend fun ping(target: String?, count: Int = 10, onStep: (Int) -> Unit = {}): Ping = withContext(Dispatchers.IO) {
        val url = target ?: FALLBACK_PING
        val times = ArrayList<Long>()
        var lost = 0
        // First request opens the connection (DNS, TCP, TLS): not counted.
        runCatching { client.newCall(Request.Builder().url(url).head().build()).execute().close() }
        for (i in 0 until count) {
            val t0 = SystemClock.elapsedRealtime()
            val ok = runCatching { client.newCall(Request.Builder().url(url).head().build()).execute().close() }.isSuccess
            if (ok) times.add(SystemClock.elapsedRealtime() - t0) else lost++
            withContext(Dispatchers.Main) { onStep(i + 1) }
            delay(150)
        }
        val avg = times.takeIf { it.isNotEmpty() }?.average()?.toInt()
        val jitter = if (times.size >= 2) times.zipWithNext { a, b -> abs(a - b) }.average().toInt() else null
        Ping(avg, jitter, lost * 100 / count)
    }

    /** Downloads for up to [maxMs] and returns Mbit/s (the first half second is warm-up and not counted). */
    suspend fun speed(maxMs: Long = 6_000, onMbps: (Double) -> Unit = {}): Double? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(SPEED_URL + "?t=" + System.currentTimeMillis()).header("Cache-Control", "no-cache").build()
            Http.client.newCall(req).execute().use { r ->
                val body = r.body ?: return@use null
                if (!r.isSuccessful) return@use null
                val input = body.byteStream()
                val buf = ByteArray(64 * 1024)
                val start = SystemClock.elapsedRealtime()
                var countFrom = 0L
                var bytes = 0L
                var lastReport = start
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    val now = SystemClock.elapsedRealtime()
                    if (now - start < 500) continue
                    if (countFrom == 0L) countFrom = now
                    bytes += n
                    if (now - lastReport > 400 && now > countFrom) {
                        lastReport = now
                        val mbps = bytes * 8.0 / ((now - countFrom) / 1000.0) / 1_000_000
                        withContext(Dispatchers.Main) { onMbps(mbps) }
                    }
                    if (now - start > maxMs) break
                }
                val secs = (SystemClock.elapsedRealtime() - countFrom) / 1000.0
                if (countFrom == 0L || secs <= 0.2) null else bytes * 8.0 / secs / 1_000_000
            }
        }.getOrNull()
    }

    fun verdict(conn: Connection, ping: Ping?, mbps: Double?): Verdict = when {
        conn.link == Link.NONE -> Verdict.OFFLINE
        ping == null || mbps == null -> Verdict.UNSTABLE
        ping.lossPct == 0 && (ping.jitterMs ?: 0) < 30 && (ping.avgMs ?: 0) < 150 && mbps >= 15 -> Verdict.STABLE
        ping.lossPct <= 10 && (ping.jitterMs ?: 0) < 70 && mbps >= 8 -> Verdict.OK
        else -> Verdict.UNSTABLE
    }
}
