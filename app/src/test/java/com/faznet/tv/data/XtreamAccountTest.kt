package com.faznet.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XtreamAccountTest {

    private fun url(source: String) = XtreamAccount.from(Playlist("1", "P", Playlist.TYPE_URL, source))

    @Test
    fun theUsualLinkHasTheApiAtTheRoot() {
        assertEquals(XtreamAccount("http://h:8080", "u", "p"), url("http://h:8080/get.php?username=u&password=p&type=m3u_plus&output=ts"))
    }

    @Test
    fun aLinkUnderASubPathKeepsIt() {
        // Before, the API was asked at http://h/player_api.php: no movies, series or guide for such providers.
        assertEquals(XtreamAccount("http://h/xc", "u", "p"), url("http://h/xc/get.php?username=u&password=p"))
        assertEquals(XtreamAccount("https://h/a/b", "u", "p"), url("https://h/a/b/get.php?username=u&password=p#x"))
    }

    @Test
    fun aLinkWithoutALoginIsNotAnAccount() {
        assertNull(url("http://h/list.m3u"))
    }

    @Test
    fun anXtreamPlaylistUsesTheServerAsTyped() {
        val p = Playlist("1", "P", Playlist.TYPE_XTREAM, "h/xc/", "u", "p")
        assertEquals(XtreamAccount("http://h/xc", "u", "p"), XtreamAccount.from(p))
    }
}
