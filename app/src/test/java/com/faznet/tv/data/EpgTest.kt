package com.faznet.tv.data

import android.app.Application
import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class EpgTest {

    private fun b64(s: String) = Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP)

    /** get_short_epg with limit=3, out of order as some servers send it; times in seconds. */
    private val body = """
        {"epg_listings":[
          {"title":"${b64("Third")}","start_timestamp":"3000","stop_timestamp":"4000"},
          {"title":"${b64("First")}","start_timestamp":"1000","stop_timestamp":"2000"},
          {"title":"${b64("Second")}","start_timestamp":"2000","stop_timestamp":"3000"}
        ]}
    """.trimIndent()

    @Test
    fun nowAndNextFromTheServersAnswer() {
        val all = Epg.parse(body)!!
        val nn = Epg.current(all, t = 1_500_000)!!
        assertEquals("First", nn.now?.title)
        assertEquals("Second", nn.next?.title)
    }

    @Test
    fun aCachedAnswerStillHasANextAfterTheFirstProgrammeEnded() {
        // Before, only two of the three programmes were kept: once the first ended there was no "next".
        val nn = Epg.current(Epg.parse(body)!!, t = 2_500_000)!!
        assertEquals("Second", nn.now?.title)
        assertEquals("Third", nn.next?.title)
    }

    @Test
    fun nothingWhenEverythingIsOver() {
        assertNull(Epg.current(Epg.parse(body)!!, t = 5_000_000))
        assertNull(Epg.parse("""{"epg_listings":[]}"""))
    }

    @Test
    fun theServerAndLoginComeFromTheChannelsAddress() {
        assertEquals(Epg.Target("http://h:8080", "u", "p", "12"), Epg.target("http://h:8080/live/u/p/12.ts", null))
        assertEquals(Epg.Target("http://h", "u", "p", "12"), Epg.target("http://h/u/p/12", null))
    }

    @Test
    fun anAddressWithASubPathOrAQueryHasNowNextToo() {
        // The form the provider's own M3U list writes (XtreamLive.Template): before, these got no now/next.
        assertEquals(Epg.Target("http://h/xc", "u", "p", "12"), Epg.target("http://h/xc/live/u/p/12.m3u8", null))
        assertEquals(Epg.Target("http://h", "u", "p", "12"), Epg.target("http://h/live/u/p/12.ts?token=abc", null))
        assertEquals(Epg.Target("https://h/a/b", "u", "p", "12"), Epg.target("https://h/a/b/u/p/12", null))
    }

    @Test
    fun anOtherFormUsesThePlaylistsAccount() {
        val acc = XtreamAccount("http://api.host", "u", "p")
        assertEquals(Epg.Target("http://api.host", "u", "p", "12"), Epg.target("http://cdn.host/play/12.ts?t=1", acc))
        assertNull("not an Xtream playlist", Epg.target("http://cdn.host/play/12.ts?t=1", null))
        assertNull(Epg.target("http://cdn.host/channel.m3u8", acc))
    }
}
