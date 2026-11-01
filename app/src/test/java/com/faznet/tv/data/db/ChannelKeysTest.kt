package com.faznet.tv.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelKeysTest {

    @Test
    fun anXtreamChannelIsKnownByItsStreamNumber() {
        assertEquals("x:12345", ChannelKeys.of("http://host:8080/live/user/pass/12345.ts", xtream = true))
        assertEquals("x:12345", ChannelKeys.of("http://host:8080/user/pass/12345", xtream = true))
        assertEquals("x:12345", ChannelKeys.of("http://other/user2/pass2/12345.m3u8?token=9", xtream = true))
    }

    @Test
    fun otherAddressesAreTheirOwnKey() {
        val named = "http://host/live/channel.m3u8"
        assertEquals(named, ChannelKeys.of(named, xtream = true))
        val noPath = "http://host"
        assertEquals("http://host", ChannelKeys.of(noPath, xtream = true))
        val plain = "http://host/live/user/pass/12345.ts"
        assertEquals(plain, ChannelKeys.of(plain, xtream = false))
    }

    @Test
    fun plainNamesNeedNoSearchForm() {
        assertNull(ChannelKeys.searchName("AL| KLAN HD [BK]"))
    }

    @Test
    fun accentsAreLeftOutOfTheSearchForm() {
        assertEquals("shqiperi", ChannelKeys.searchName("SHQIPËRI"))
        assertEquals("camëria".replace("ë", "e"), ChannelKeys.searchName("Çamëria"))
        assertEquals("✦●✦ gjenerale ✦●✦", ChannelKeys.searchName("✦●✦ GJENERALE ✦●✦"))
    }
}
