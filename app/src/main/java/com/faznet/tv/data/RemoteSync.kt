package com.faznet.tv.data

import android.os.Build
import com.faznet.tv.BuildConfig
import com.faznet.tv.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/** Talks to the Faznet upload server: registers this device's MAC + PIN and pulls playlists added on the website. */
object RemoteSync {

    sealed class Result {
        data class Ok(val count: Int, val changed: Boolean) : Result()
        data class Error(val message: String) : Result()
        object NoServer : Result()
    }

    private val lock = Mutex()
    /** Register once per app run; the server keeps it. Re-registers if the server forgot us. */
    @Volatile private var registered = false
    private val json = "application/json; charset=utf-8".toMediaType()

    suspend fun sync(repo: Repository): Result = lock.withLock {
        withContext(Dispatchers.IO) {
            val server = repo.serverUrl
            if (server.isEmpty()) return@withContext Result.NoServer
            try {
                if (!registered) ensureRegistered(server, repo)
                var resp = fetchPlaylists(server, repo)
                if (resp.code == 403) {
                    // Server lost or never saw this device (e.g. new server): register and retry once.
                    resp.close()
                    ensureRegistered(server, repo)
                    resp = fetchPlaylists(server, repo)
                }
                resp.use { resp ->
                    if (!resp.isSuccessful) return@withContext Result.Error("HTTP ${resp.code}")
                    val arr = JSONObject(resp.body?.string() ?: "{}").optJSONArray("playlists")
                    val list = ArrayList<Playlist>()
                    if (arr != null) for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val rid = o.getLong("id")
                        val type = o.optString("type")
                        list += if (type == "xtream") {
                            Playlist("r$rid", o.optString("name"), Playlist.TYPE_XTREAM, o.optString("server"),
                                o.optString("username"), o.optString("password"), remoteId = rid)
                        } else {
                            Playlist("r$rid", o.optString("name"), Playlist.TYPE_URL, o.optString("url"), remoteId = rid)
                        }
                    }
                    val changed = repo.mergeRemote(list)
                    repo.lastSync = System.currentTimeMillis()
                    Result.Ok(list.size, changed)
                }
            } catch (e: IOException) {
                Result.Error(e.message ?: e.javaClass.simpleName)
            } catch (e: Exception) {
                Result.Error(e.javaClass.simpleName)
            }
        }
    }

    private fun fetchPlaylists(server: String, repo: Repository) = Http.api.newCall(
        Request.Builder().url("$server/api/device/playlists")
            .header("X-Faznet-Mac", repo.deviceMac)
            .header("X-Faznet-Pin", repo.devicePin)
            .build()
    ).execute()

    private fun ensureRegistered(server: String, repo: Repository) {
        if (!register(server, repo)) {
            // Extremely unlikely MAC collision: pick a new identity and try once more.
            repo.newDeviceIdentity()
            if (!register(server, repo)) throw IOException("register")
        }
        registered = true
    }

    /** @return false only when the server says this MAC already belongs to another PIN. */
    private fun register(server: String, repo: Repository): Boolean {
        val body = JSONObject()
            .put("mac", repo.deviceMac)
            .put("pin", repo.devicePin)
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            .put("version", BuildConfig.VERSION_NAME)
            .toString().toRequestBody(json)
        val req = Request.Builder().url("$server/api/device/register").post(body).build()
        Http.api.newCall(req).execute().use { resp ->
            if (resp.code == 409) return false
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
        }
        return true
    }
}
