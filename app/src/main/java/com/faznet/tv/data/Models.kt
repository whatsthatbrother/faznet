package com.faznet.tv.data

import org.json.JSONObject

data class Channel(
    val number: Int,
    val name: String,
    val url: String,
    val logo: String?,
    val group: String,
    val userAgent: String?,
    val referrer: String?,
)

data class Playlist(
    val id: String,
    val name: String,
    val type: String,
    val source: String,
    val username: String = "",
    val password: String = "",
    /** Id on the Faznet upload server; 0 for playlists added on the device itself. */
    val remoteId: Long = 0,
) {
    val isRemote: Boolean get() = remoteId != 0L

    /** URL the M3U is downloaded from (null for local files). */
    fun m3uUrl(): String? = when (type) {
        TYPE_XTREAM -> "${normalizeServer(source)}/get.php?username=${enc(username)}&password=${enc(password)}&type=m3u_plus&output=ts"
        TYPE_URL -> source
        else -> null
    }


    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("type", type).put("source", source)
        .put("username", username).put("password", password).put("remoteId", remoteId)

    companion object {
        const val TYPE_URL = "url"
        const val TYPE_XTREAM = "xtream"
        const val TYPE_FILE = "file"

        fun fromJson(o: JSONObject) = Playlist(
            id = o.optString("id"),
            name = o.optString("name"),
            type = o.optString("type", TYPE_URL),
            source = o.optString("source"),
            username = o.optString("username"),
            password = o.optString("password"),
            remoteId = o.optLong("remoteId", 0),
        )

        fun normalizeServer(raw: String): String {
            var s = raw.trim().trimEnd('/')
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
            return s
        }

        private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
    }
}

/** Error text that never reveals playlist addresses, hosts or IPs (the app must not show where a playlist comes from). */
fun Throwable.safeMessage(): String {
    val m = message?.takeIf { it.isNotBlank() } ?: return javaClass.simpleName
    return m.replace(Regex("""[a-zA-Z][a-zA-Z0-9+.-]*://\S+"""), "…")
        .replace(Regex("""\b\d{1,3}(?:\.\d{1,3}){3}(?::\d+)?\b"""), "…")
        .replace(Regex("""(?i)\b(?:[a-z0-9-]+\.)+[a-z]{2,}(?::\d+)?\b"""), "…")
        .take(120)
}
