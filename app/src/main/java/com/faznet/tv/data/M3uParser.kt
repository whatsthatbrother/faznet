package com.faznet.tv.data

import java.io.BufferedReader

/** Tolerant parser for extended M3U playlists (#EXTINF, #EXTGRP, #EXTVLCOPT, Kodi-style "url|User-Agent=..."). */
object M3uParser {
    private val attrRegex = Regex("""([A-Za-z0-9_\-]+)\s*=\s*"([^"]*)"""")

    fun parse(reader: BufferedReader, defaultGroup: String): List<Channel> {
        val out = ArrayList<Channel>(1024)
        var name: String? = null
        var logo: String? = null
        var group: String? = null
        var extGroup: String? = null
        var ua: String? = null
        var ref: String? = null

        while (true) {
            val raw = reader.readLine() ?: break
            val line = raw.trim().removePrefix("﻿")
            if (line.isEmpty()) continue

            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                val body = line.substringAfter(':', "")
                val comma = findNameComma(body)
                val attrs = if (comma >= 0) body.substring(0, comma) else body
                name = if (comma >= 0) body.substring(comma + 1).trim() else null
                logo = null; group = null; ua = null; ref = null
                for (m in attrRegex.findAll(attrs)) {
                    val key = m.groupValues[1].lowercase()
                    val value = m.groupValues[2].trim()
                    if (value.isEmpty()) continue
                    when (key) {
                        "tvg-logo", "logo" -> logo = value
                        "group-title" -> group = value
                        "tvg-name" -> if (name.isNullOrEmpty()) name = value
                        "user-agent", "http-user-agent" -> ua = value
                        "referer", "referrer", "http-referrer", "http-referer" -> ref = value
                    }
                }
            } else if (line.startsWith("#EXTGRP:", ignoreCase = true)) {
                extGroup = line.substring(8).trim().ifEmpty { null }
            } else if (line.startsWith("#EXTVLCOPT:", ignoreCase = true)) {
                val opt = line.substring(11)
                val key = opt.substringBefore('=').trim().lowercase()
                val value = opt.substringAfter('=', "").trim()
                if (value.isNotEmpty()) {
                    if (key == "http-user-agent") ua = value
                    else if (key == "http-referrer" || key == "http-referer") ref = value
                }
            } else if (line.startsWith("#")) {
                // other directives (#EXTM3U, #KODIPROP, ...) are ignored
            } else {
                var url = line
                val pipe = url.indexOf('|')
                if (pipe > 0) {
                    val opts = url.substring(pipe + 1)
                    url = url.substring(0, pipe)
                    for (part in opts.split('&')) {
                        val k = part.substringBefore('=').trim().lowercase()
                        val v = java.net.URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
                        if (k == "user-agent") ua = v
                        else if (k == "referer" || k == "referrer") ref = v
                    }
                }
                val title = name?.takeIf { it.isNotEmpty() }
                    ?: url.substringAfterLast('/').substringBefore('?').ifEmpty { url }
                out.add(
                    Channel(
                        number = out.size + 1,
                        name = title,
                        url = url,
                        logo = logo,
                        group = group ?: extGroup ?: defaultGroup,
                        userAgent = ua,
                        referrer = ref,
                    )
                )
                name = null; logo = null; group = null; extGroup = null; ua = null; ref = null
            }
        }
        return out
    }

    /** Index of the comma separating attributes from the display name (ignores commas inside quotes). */
    private fun findNameComma(s: String): Int {
        var inQuote = false
        for (i in s.indices) {
            val c = s[i]
            if (c == '"') inQuote = !inQuote
            else if (c == ',' && !inQuote) return i
        }
        return -1
    }
}
