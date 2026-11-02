package com.faznet.tv.data

import android.app.Application
import android.os.Looper
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class NetTestTest {

    private val wall: () -> Long = { System.nanoTime() / 1_000_000 }
    private val wifi = NetTest.Connection(NetTest.Link.WIFI, 90)
    private val goodPing = NetTest.Ping(30, 5, 0)

    /** The speed test reports to the main thread, which a test has to turn by hand. */
    private fun measure(url: String, maxMs: Long, maxBytes: Long = Long.MAX_VALUE): Double? {
        var result: Double? = null
        val t = Thread { result = runBlocking { NetTest.speed(maxMs, url, maxBytes, wall) } }
        t.start()
        while (t.isAlive) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        return result
    }

    private fun serve(size: Int, count: AtomicInteger): HttpServer {
        val body = ByteArray(size)
        return HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/f") { x ->
                count.incrementAndGet()
                x.sendResponseHeaders(200, body.size.toLong())
                x.responseBody.use { it.write(body) }
            }
            start()
        }
    }

    /** The test file is 4.6 MB since VLC went: a fast line finished it inside the warm-up, and was called unstable. */
    @Test
    fun aFileSmallerThanTheLineIsFastIsFetchedAgain() {
        val hits = AtomicInteger()
        val server = serve(4_600_000, hits)
        val mbps = measure("http://127.0.0.1:${server.address.port}/f", maxMs = 1_500)
        server.stop(0)
        assertNotNull("a fast line has to be measured", mbps)
        assertTrue("about ${mbps} Mbit/s", mbps!! > 50)
        assertTrue("fetched ${hits.get()} times", hits.get() > 1)
    }

    @Test
    fun theTestStopsAtItsLimitOfData() {
        val hits = AtomicInteger()
        val server = serve(1_000_000, hits)
        val started = wall()
        measure("http://127.0.0.1:${server.address.port}/f", maxMs = 6_000, maxBytes = 2_000_000)
        server.stop(0)
        assertTrue("it took ${wall() - started} ms", wall() - started < 5_000)
    }

    @Test
    fun noWebsiteNoSpeed() {
        assertNull(measure("http://127.0.0.1:9/f", maxMs = 500))
    }

    @Test
    fun aSpeedThatCouldNotBeMeasuredIsNoVerdictOnTheLine() {
        assertEquals(NetTest.Verdict.OK, NetTest.verdict(wifi, goodPing, null))
        assertEquals(NetTest.Verdict.UNSTABLE, NetTest.verdict(wifi, NetTest.Ping(30, 5, 20), null))
    }

    @Test
    fun verdicts() {
        assertEquals(NetTest.Verdict.STABLE, NetTest.verdict(wifi, goodPing, 80.0))
        assertEquals(NetTest.Verdict.OK, NetTest.verdict(wifi, goodPing, 10.0))
        assertEquals(NetTest.Verdict.UNSTABLE, NetTest.verdict(wifi, goodPing, 3.0))
        assertEquals(NetTest.Verdict.UNSTABLE, NetTest.verdict(wifi, null, 80.0))
        assertEquals(NetTest.Verdict.OFFLINE, NetTest.verdict(NetTest.Connection(NetTest.Link.NONE, null), null, null))
    }
}
