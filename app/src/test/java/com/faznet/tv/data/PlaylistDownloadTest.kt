package com.faznet.tv.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PlaylistDownloadTest {

    private lateinit var server: HttpServer
    private lateinit var repo: Repository
    private lateinit var dest: File
    private val part get() = File(dest.parentFile, dest.name + ".part")
    private val sent = AtomicLong()

    @Before
    fun open() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Repository::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        repo = Repository.get(app)
        dest = repo.cacheFile("p")
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.start()
    }

    @After
    fun close() = server.stop(0)

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    /** Sends what [body] writes, without a length: a live stream looks like this. */
    private fun serve(path: String, body: (java.io.OutputStream) -> Unit) {
        server.createContext(path) { x: HttpExchange ->
            x.sendResponseHeaders(200, 0)
            try {
                x.responseBody.use(body)
            } catch (e: IOException) {
                // The app hung up: that is what these tests are about.
            }
        }
    }

    private fun forever(chunk: ByteArray): (java.io.OutputStream) -> Unit = { out ->
        while (true) {
            out.write(chunk)
            sent.addAndGet(chunk.size.toLong())
        }
    }

    @Test
    fun aWholeListArrives() {
        val list = "#EXTM3U\n#EXTINF:-1,A\nhttp://a.test/1.ts\n"
        serve("/l") { it.write(list.toByteArray()) }
        repo.download(url("/l"), dest)
        assertEquals(list, dest.readText())
        assertFalse(part.exists())
    }

    @Test
    fun aListWithoutHeaderOfPlainAddressesIsStillAList() {
        serve("/l") { it.write("http://a.test/1.ts\nhttp://a.test/2.ts\n".toByteArray()) }
        repo.download(url("/l"), dest)
        assertTrue(dest.exists())
    }

    /** Someone put in the address of a channel: it never ends, and used to be written to the device until that was full. */
    @Test
    fun aChannelAddressIsDroppedAtOnce() {
        serve("/stream", forever(ByteArray(64 * 1024) { 0x47 }))
        try {
            repo.download(url("/stream"), dest)
            fail("this is no playlist")
        } catch (e: IOException) {
            // expected
        }
        assertFalse(dest.exists())
        assertFalse(part.exists())
        assertTrue("it hung up early, after ${sent.get()} bytes", sent.get() < 16 * 1024 * 1024)
    }

    @Test
    fun aListThatNeverEndsStopsAtTheLimit() {
        val line = "#EXTINF:-1,A\nhttp://a.test/1.ts\n".repeat(1000).toByteArray()
        serve("/endless", forever(line))
        try {
            repo.download(url("/endless"), dest, maxBytes = 1024 * 1024)
            fail("too big")
        } catch (e: IOException) {
            // expected
        }
        assertFalse(dest.exists())
        assertFalse(part.exists())
    }

    @Test
    fun aDownloadThatFailsLeavesTheOldFile() {
        dest.writeText("old")
        server.createContext("/cut") { x: HttpExchange ->
            x.sendResponseHeaders(200, 100_000)
            x.responseBody.write("#EXTM3U\n#EXTINF:-1,A\nhttp://a.test/1.ts\n".toByteArray())
            x.responseBody.flush()
            x.close()
        }
        try {
            repo.download(url("/cut"), dest)
            fail("it was cut off")
        } catch (e: IOException) {
            // expected
        }
        assertEquals("old", dest.readText())
        assertFalse(part.exists())
    }

    /** Another playlist was chosen, or the screen left: a big download does not go on for nothing. */
    @Test
    fun aDownloadStopsWhenItIsNoLongerWanted() {
        val line = "#EXTINF:-1,A\nhttp://a.test/1.ts\n".repeat(1000).toByteArray()
        serve("/big", forever(line))
        var asked = 0
        try {
            repo.download(url("/big"), dest) { if (++asked > 3) throw CancellationException("stop") }
            fail("it was stopped")
        } catch (e: CancellationException) {
            // expected
        }
        assertFalse(dest.exists())
        assertFalse(part.exists())
    }
}
