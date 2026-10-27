package xie.fa.gram.helpers.lastfm

import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.BuildVars
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

data class LastFmTrack(
    val title: String,
    val artist: String,
    val album: String,
    val trackUrl: String,
    val coverUrl: String?,
    val isNowPlaying: Boolean
)

object LastFmApiClient {
    private const val TAG = "LastFmApiClient"
    private const val BASE_URL = "https://ws.audioscrobbler.com/2.0/"
    private const val CACHE_TTL_MS = 15_000L

    private data class CacheEntry(
        val timestamp: Long,
        val track: LastFmTrack?
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    interface Callback {
        fun onResult(track: LastFmTrack?)
    }

    /**
     * Fetches current/recent track for [username].
     * Uses in-memory cache if queried within 15s TTL.
     */
    @JvmStatic
    fun fetchRecentTrack(account: Int, username: String, isSelf: Boolean, callback: Callback) {
        val trimmedUser = username.trim()
        if (trimmedUser.isEmpty()) {
            callback.onResult(null)
            return
        }

        val cacheKey = trimmedUser.lowercase()
        val cached = cache[cacheKey]
        val now = System.currentTimeMillis()
        if (cached != null && (now - cached.timestamp) < CACHE_TTL_MS) {
            callback.onResult(cached.track)
            return
        }

        val apiKey = LastFmStorage.resolveApiKey(account, isSelf)
        if (apiKey.isEmpty()) {
            Log.w(TAG, "No Last.fm API key available for request")
            callback.onResult(null)
            return
        }

        Utilities.globalQueue.postRunnable {
            val track = performFetch(trimmedUser, apiKey)
            cache[cacheKey] = CacheEntry(System.currentTimeMillis(), track)
            callback.onResult(track)
        }
    }

    @JvmStatic
    fun fetchRecentTrack(username: String, isSelf: Boolean, callback: Callback) {
        fetchRecentTrack(UserConfig.selectedAccount, username, isSelf, callback)
    }

    private fun performFetch(username: String, apiKey: String): LastFmTrack? {
        var conn: HttpURLConnection? = null
        try {
            val urlString = Uri.parse(BASE_URL).buildUpon()
                .appendQueryParameter("method", "user.getrecenttracks")
                .appendQueryParameter("user", username)
                .appendQueryParameter("api_key", apiKey)
                .appendQueryParameter("format", "json")
                .appendQueryParameter("limit", "1")
                .build()
                .toString()

            val userAgent = "FAgramAndroid/" + BuildVars.BUILD_VERSION_STRING
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
                connectTimeout = 10_000
                readTimeout = 10_000
            }

            if (conn.responseCode !in 200..299) {
                return null
            }

            val bodyString = conn.inputStream.bufferedReader().use { it.readText() }
            return parseTrack(bodyString, username)
        } catch (e: Exception) {
            // Any network failure, malformed JSON -> treat as "no data", no retry, no user-facing error
            return null
        } finally {
            conn?.disconnect()
        }
    }

    private fun parseTrack(json: String, username: String): LastFmTrack? {
        return try {
            val root = JSONObject(json)
            val recentTracks = root.optJSONObject("recenttracks") ?: return null
            val trackField = recentTracks.opt("track") ?: return null

            val trackObj = when (trackField) {
                is JSONArray -> trackField.optJSONObject(0)
                is JSONObject -> trackField
                else -> null
            } ?: return null

            val attr = trackObj.optJSONObject("@attr")
            val isNowPlaying = attr?.optString("nowplaying", "false") == "true"

            val rawTitle = trackObj.optString("name", "").trim()
            val title = if (rawTitle.isNotEmpty()) rawTitle else "Unknown Track"

            val rawArtist = parseTextOrObject(trackObj.opt("artist"))
            val artist = if (rawArtist.isNotEmpty()) rawArtist else "Unknown Artist"

            val album = parseTextOrObject(trackObj.opt("album"))

            val rawUrl = trackObj.optString("url", "").trim()
            val trackUrl = if (rawUrl.isNotEmpty()) rawUrl else "https://www.last.fm/user/" + Uri.encode(username)

            val coverUrl = selectCoverUrl(trackObj.optJSONArray("image"))

            LastFmTrack(
                title = title,
                artist = artist,
                album = album,
                trackUrl = trackUrl,
                coverUrl = coverUrl,
                isNowPlaying = isNowPlaying
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseTextOrObject(obj: Any?): String {
        return when (obj) {
            is JSONObject -> obj.optString("#text", "").trim()
            is String -> obj.trim()
            else -> ""
        }
    }

    private fun selectCoverUrl(images: JSONArray?): String? {
        if (images == null) return null
        var largeUrl: String? = null
        var mediumUrl: String? = null
        var firstNonEmptyUrl: String? = null

        for (i in 0 until images.length()) {
            val img = images.optJSONObject(i) ?: continue
            val size = img.optString("size", "").trim()
            val text = img.optString("#text", "").trim()
            if (text.isNotEmpty()) {
                if (firstNonEmptyUrl == null) firstNonEmptyUrl = text
                if (size == "large" && largeUrl == null) largeUrl = text
                if (size == "medium" && mediumUrl == null) mediumUrl = text
            }
        }
        return largeUrl ?: mediumUrl ?: firstNonEmptyUrl
    }
}
