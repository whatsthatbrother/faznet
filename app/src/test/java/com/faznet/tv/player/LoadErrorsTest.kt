package com.faznet.tv.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.SocketTimeoutException

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class LoadErrorsTest {

    private val spec = DataSpec(Uri.parse("http://line.example.test/live/u/p/1.ts"))

    private fun http(code: Int) = HttpDataSource.InvalidResponseCodeException(
        code, null, null, emptyMap(), spec, ByteArray(0),
    )

    private fun delay(opening: Boolean, e: IOException, count: Int = 1): Long {
        val event = LoadEventInfo(1, spec, 0)
        val data = MediaLoadData(C.DATA_TYPE_MEDIA)
        return OpeningErrorPolicy { opening }.getRetryDelayMsFor(LoadErrorHandlingPolicy.LoadErrorInfo(event, data, e, count))
    }

    @Test
    fun aRefusalWhileOpeningGoesStraightToThePlayer() {
        assertEquals(C.TIME_UNSET, delay(opening = true, http(403)))
        assertEquals(C.TIME_UNSET, delay(opening = true, http(404)))
    }

    @Test
    fun onceItPlaysMedia3RetriesARefusalItself() {
        assertTrue(delay(opening = false, http(403), count = 2) > 0)
    }

    @Test
    fun aServerOrNetworkErrorIsStillRetriedWhileOpening() {
        assertTrue(delay(opening = true, http(503), count = 2) > 0)
        assertTrue(delay(opening = true, SocketTimeoutException("slow"), count = 2) > 0)
    }

    @Test
    fun theKindOfErrorIsOneWordWithoutTheAddress() {
        assertEquals("http403", loadErrorKind(http(403)))
        assertEquals("timeout", loadErrorKind(IOException("wrapped", SocketTimeoutException())))
        val other = loadErrorKind(IOException("Unable to connect to http://line.example.test/live/u/p/1.ts"))
        assertEquals("other", other)
        assertFalse(other.contains("example"))
    }
}
