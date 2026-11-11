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
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
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

    /**
     * [count] connections one after another: delay, jitter and how many got no answer.
     * Each one is only a TCP connect to the server's port (nothing is sent): many IPTV panels drop HTTP
     * requests to their root page, which made a perfectly good server look 100% lost.
     */
    suspend fun ping(target: String?, count: Int = 10, onStep: (Int) -> Unit = {}): Ping = withContext(Dispatchers.IO) {
        val url = (target ?: FALLBACK_PING).toHttpUrlOrNull()
        // The name is looked up once here, so the times below are the connection alone.
        val address = url?.let { u -> runCatching { InetSocketAddress(InetAddress.getByName(u.host), u.port) }.getOrNull() }
        fun connect() = address != null && runCatching { Socket().use { it.connect(address, 3_000) } }.isSuccess
        val times = ArrayList<Long>()
        var lost = 0
        connect() // warm-up (route, ARP), not counted
        for (i in 0 until count) {
            val t0 = SystemClock.elapsedRealtime()
            val ok = connect()
            if (ok) times.add(SystemClock.elapsedRealtime() - t0) else lost++
            withContext(Dispatchers.Main) { onStep(i + 1) }
            delay(150)
        }
        val avg = times.takeIf { it.isNotEmpty() }?.average()?.toInt()
        val jitter = if (times.size >= 2) times.zipWithNext { a, b -> abs(a - b) }.average().toInt() else null
        Ping(avg, jitter, lost * 100 / count)
    }

    /**
     * Downloads for up to [maxMs] and returns Mbit/s (the first half second is warm-up and not counted). The file is only
     * a few MB: a fast line would be done with it during the warm-up, so it is fetched again until the time is up, or
     * until [maxBytes] were counted.
     */
    suspend fun speed(
        maxMs: Long = 6_000,
        url: String = SPEED_URL,
        maxBytes: Long = 40L * 1024 * 1024,
        clock: () -> Long = SystemClock::elapsedRealtime,
        onMbps: (Double) -> Unit = {},
    ): Double? = withContext(Dispatchers.IO) {
        val start = clock()
        var countFrom = 0L
        var bytes = 0L
        var lastReport = start
        val buf = ByteArray(64 * 1024)
        try {
            while (clock() - start < maxMs && bytes < maxBytes) {
                val req = Request.Builder().url(url + "?t=" + System.nanoTime()).header("Cache-Control", "no-cache").build()
                Http.client.newCall(req).execute().use { r ->
                    val body = r.body
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    val input = body.byteStream()
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        val now = clock()
                        if (now - start < 500) continue
                        if (countFrom == 0L) countFrom = now
                        bytes += n
                        if (now - lastReport > 400 && now > countFrom) {
                            lastReport = now
                            val mbps = bytes * 8.0 / ((now - countFrom) / 1000.0) / 1_000_000
                            withContext(Dispatchers.Main) { onMbps(mbps) }
                        }
                        if (now - start > maxMs || bytes >= maxBytes) break
                    }
                }
            }
        } catch (e: IOException) {
            // What was measured until then still says something.
        }
        val secs = (clock() - countFrom) / 1000.0
        if (countFrom == 0L || secs <= 0.2) null else bytes * 8.0 / secs / 1_000_000
    }

    fun verdict(conn: Connection, ping: Ping?, mbps: Double?): Verdict = when {
        conn.link == Link.NONE -> Verdict.OFFLINE
        ping == null -> Verdict.UNSTABLE
        // The test file could not be fetched: that is about the website, not about this line.
        mbps == null -> if (ping.lossPct == 0 && (ping.jitterMs ?: 0) < 30 && (ping.avgMs ?: 0) < 150) Verdict.OK else Verdict.UNSTABLE
        ping.lossPct == 0 && (ping.jitterMs ?: 0) < 30 && (ping.avgMs ?: 0) < 150 && mbps >= 15 -> Verdict.STABLE
        ping.lossPct <= 10 && (ping.jitterMs ?: 0) < 70 && mbps >= 8 -> Verdict.OK
        else -> Verdict.UNSTABLE
    }
}
