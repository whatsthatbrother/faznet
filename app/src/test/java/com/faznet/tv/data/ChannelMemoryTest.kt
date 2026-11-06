package com.faznet.tv.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class ChannelMemoryTest {

    private val c get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val url = "http://example.test/live/u/p/18.ts"

    @Test
    fun whatVersionsBeforeThisSavedStillReads() {
        assertEquals(ChannelMemory.Info("PROGRESSIVE", 1080, 2, software = false), ChannelMemory.parse("PROGRESSIVE|1080|2"))
        assertEquals(ChannelMemory.Info(null, 0, 0, software = false), ChannelMemory.parse("|0|0"))
    }

    @Test
    fun theSoftwareChoiceIsKept() {
        val info = ChannelMemory.Info("HLS", 1080, 0, software = true)
        assertEquals(info, ChannelMemory.parse(ChannelMemory.format(info)))
    }

    @Test
    fun useSoftwareKeepsWhatElseWasLearned() {
        assertNull(ChannelMemory.get(c, url))
        ChannelMemory.rememberType(c, url, "PROGRESSIVE")
        ChannelMemory.rememberHeight(c, url, 1080)
        ChannelMemory.useSoftware(c, url, true)
        val info = ChannelMemory.get(c, url)!!
        assertTrue(info.software)
        assertEquals("PROGRESSIVE", info.type)
        assertEquals(1080, info.height)
        ChannelMemory.useSoftware(c, url, false)
        assertFalse(ChannelMemory.get(c, url)!!.software)
        assertEquals(1080, ChannelMemory.get(c, url)!!.height)
    }
}
