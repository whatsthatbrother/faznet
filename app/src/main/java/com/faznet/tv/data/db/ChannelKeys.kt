package com.faznet.tv.data.db

/**
 * What identifies a channel across imports of the same playlist. In an Xtream address the account is part of the
 * path (`/live/user/pass/12345.ts`), so favourites kept by address were lost whenever the password or the server's
 * name changed; the stream number stays the same. Other playlists have only the address to go by.
 */
object ChannelKeys {

    fun of(url: String, xtream: Boolean): String = (if (xtream) streamId(url) else null) ?: url

    /** The number at the end of the address, without an extension or a query; null if it does not end in one. */
    private fun streamId(url: String): String? {
        var end = url.indexOf('?').let { if (it >= 0) it else url.length }
        val slash = url.lastIndexOf('/', end - 1)
        if (slash < 0) return null
        val dot = url.lastIndexOf('.', end - 1)
        if (dot > slash) end = dot
        if (end <= slash + 1) return null
        for (i in slash + 1 until end) if (url[i] !in '0'..'9') return null
        return "x:" + url.substring(slash + 1, end)
    }

    /**
     * Name in the form the search compares: lower case without accents, so "shqiperi" finds "SHQIPËRI". Null for plain
     * ASCII names, which SQLite's LIKE compares without regard to case by itself; most names are, and this saves storing
     * them twice.
     */
    fun searchName(name: String): String? {
        var ascii = true
        for (c in name) if (c.code >= 0x80) { ascii = false; break }
        return if (ascii) null else normalize(name)
    }

    /** The same for what the user typed. */
    fun normalize(text: String): String {
        val decomposed = java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        for (c in decomposed) if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) out.append(c)
        return out.toString()
    }

    /** Some providers write their names for a web page: "Tom &amp; Jerry". */
    fun plainName(name: String): String {
        if ('&' !in name) return name
        var out = name
        while ("&amp;" in out) out = out.replace("&amp;", "&")
        return out.replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")
    }
}
