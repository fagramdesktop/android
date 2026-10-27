package xie.fa.gram.helpers.lastfm

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import org.telegram.messenger.BuildVars
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object MusicBrainzThrottler {
    private val lock = Any()
    private var nextAllowedTime = 0L

    fun throttle() {
        var waitMs = 0L
        synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            if (now < nextAllowedTime) {
                waitMs = nextAllowedTime - now
                nextAllowedTime += 1000L
            } else {
                nextAllowedTime = now + 1000L
            }
        }
        if (waitMs > 0) {
            try {
                Thread.sleep(waitMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}

object LastFmCoverFallbackResolver {
    private const val TAG = "LastFmCoverFallback"
    private const val TIMEOUT_MS = 5000L

    private val executor = Executors.newCachedThreadPool()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    private val inFlightLock = Any()
    private val inFlight = HashMap<String, MutableList<(String?) -> Unit>>()

    fun resolve(artist: String, track: String, callback: (String?) -> Unit) {
        val key = LastFmCoverCache.normalizeKey(artist, track)
        if (key.isEmpty()) {
            callback(null)
            return
        }

        // 1. Check fallback cache first
        when (val cached = LastFmCoverCache.get(artist, track)) {
            is CoverCacheResult.Hit -> {
                callback(cached.url)
                return
            }
            is CoverCacheResult.Negative -> {
                callback(null)
                return
            }
            CoverCacheResult.Miss -> {}
        }

        // 2. Deduplicate in-flight requests
        synchronized(inFlightLock) {
            val existing = inFlight[key]
            if (existing != null) {
                existing.add(callback)
                return
            }
            inFlight[key] = mutableListOf(callback)
        }

        // 3. Parallel Race: iTunes vs MusicBrainz+CAA with 5s overall timeout
        val resolved = AtomicBoolean(false)
        val pending = AtomicInteger(2)
        var timeoutFuture: ScheduledFuture<*>? = null

        fun finishWith(url: String?) {
            timeoutFuture?.cancel(false)
            LastFmCoverCache.put(artist, track, url)
            val callbacks: List<(String?) -> Unit>
            synchronized(inFlightLock) {
                callbacks = inFlight.remove(key) ?: emptyList()
            }
            for (cb in callbacks) {
                try {
                    cb(url)
                } catch (e: Exception) {
                    Log.e(TAG, "Callback error for $key", e)
                }
            }
        }

        timeoutFuture = scheduler.schedule({
            if (resolved.compareAndSet(false, true)) {
                finishWith(null)
            }
        }, TIMEOUT_MS, TimeUnit.MILLISECONDS)

        fun onCandidateResult(candidateUrl: String?) {
            if (!candidateUrl.isNullOrEmpty()) {
                if (resolved.compareAndSet(false, true)) {
                    finishWith(candidateUrl)
                }
            } else {
                if (pending.decrementAndGet() == 0) {
                    if (resolved.compareAndSet(false, true)) {
                        finishWith(null)
                    }
                }
            }
        }

        executor.execute {
            val itunesUrl = fetchItunesArt(artist, track)
            onCandidateResult(itunesUrl)
        }

        executor.execute {
            val mbUrl = fetchMusicBrainzArt(artist, track)
            onCandidateResult(mbUrl)
        }
    }

    private fun fetchItunesArt(artist: String, track: String): String? {
        var conn: HttpURLConnection? = null
        try {
            val term = "$artist $track".trim()
            val url = Uri.parse("https://itunes.apple.com/search").buildUpon()
                .appendQueryParameter("term", term)
                .appendQueryParameter("entity", "song")
                .appendQueryParameter("limit", "1")
                .build()
                .toString()

            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "FAgramAndroid/" + BuildVars.BUILD_VERSION_STRING)
                connectTimeout = 4000
                readTimeout = 4000
            }

            if (conn.responseCode !in 200..299) return null

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            val results = root.optJSONArray("results") ?: return null
            if (results.length() == 0) return null

            val first = results.getJSONObject(0)
            val art = first.optString("artworkUrl100", "").trim()
            if (art.isEmpty()) return null

            // Upsize 100x100 to 600x600
            return art.replace("100x100", "600x600")
        } catch (_: Exception) {
            return null
        } finally {
            conn?.disconnect()
        }
    }

    private fun fetchMusicBrainzArt(artist: String, track: String): String? {
        val userAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36"
        var conn: HttpURLConnection? = null
        try {
            // App-wide rate limit: at most 1 request per second across the whole app
            MusicBrainzThrottler.throttle()

            val escapedArtist = escapeLucene(artist.trim())
            val escapedTrack = escapeLucene(track.trim())
            val query = "$escapedArtist AND $escapedTrack"

            val url = Uri.parse("https://musicbrainz.org/ws/2/recording/").buildUpon()
                .appendQueryParameter("query", query)
                .appendQueryParameter("fmt", "json")
                .appendQueryParameter("limit", "1")
                .build()
                .toString()

            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/json")
                connectTimeout = 4000
                readTimeout = 4000
            }

            if (conn.responseCode !in 200..299) return null

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            val recordings = root.optJSONArray("recordings") ?: return null
            if (recordings.length() == 0) return null

            val recording = recordings.getJSONObject(0)
            val releases = recording.optJSONArray("releases") ?: return null
            if (releases.length() == 0) return null

            val releaseId = releases.getJSONObject(0).optString("id", "").trim()
            if (releaseId.isEmpty()) return null

            return resolveCaaFrontCover(releaseId, userAgent)
        } catch (_: Exception) {
            return null
        } finally {
            conn?.disconnect()
        }
    }

    private fun resolveCaaFrontCover(releaseId: String, userAgent: String): String? {
        var currentUrl = "https://coverartarchive.org/release/$releaseId/front"
        for (redirect in 0..3) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", userAgent)
                    connectTimeout = 4000
                    readTimeout = 4000
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                    if (!location.isNullOrEmpty()) {
                        currentUrl = location
                        continue
                    } else {
                        return null
                    }
                } else if (code in 200..299) {
                    return currentUrl
                } else {
                    return null
                }
            } catch (_: Exception) {
                return null
            } finally {
                conn?.disconnect()
            }
        }
        return null
    }

    private fun escapeLucene(text: String): String {
        val sb = StringBuilder()
        for (c in text) {
            if (c in "\\+-!():^[]\"{}~*?|&/") {
                sb.append('\\')
            }
            sb.append(c)
        }
        return sb.toString()
    }
}
