package xie.fa.gram.helpers.lastfm

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject
import org.telegram.messenger.ApplicationLoader

data class CachedTrack(
    val track: LastFmTrack,
    val fetchedAt: Long,
    val palette: LastFmPalette? = null
)

object LastFmTrackCache {
    private const val TAG = "LastFmTrackCache"
    private const val PREFS_FILE = "lastfm_track_cache"
    private const val MAX_ENTRIES = 100

    private var prefs: SharedPreferences? = null

    private fun getPrefs(): SharedPreferences {
        prefs?.let { return it }
        synchronized(this) {
            prefs?.let { return it }
            val p = ApplicationLoader.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            prefs = p
            return p
        }
    }

    @Synchronized
    fun get(username: String): CachedTrack? {
        val key = username.trim().lowercase()
        if (key.isEmpty()) return null
        val jsonString = getPrefs().getString(key, null) ?: return null
        return try {
            val obj = JSONObject(jsonString)
            val title = obj.optString("title", "")
            val artist = obj.optString("artist", "")
            val album = obj.optString("album", "")
            val trackUrl = obj.optString("trackUrl", "")
            val coverUrl = if (obj.has("coverUrl") && !obj.isNull("coverUrl")) obj.optString("coverUrl").takeIf { it.isNotEmpty() } else null
            val isNowPlaying = obj.optBoolean("isNowPlaying", false)
            val fetchedAt = obj.optLong("fetchedAt", 0L)

            val palette = if (obj.has("color1") && obj.has("color2") && obj.has("accentColor")) {
                LastFmPalette(
                    color1 = obj.optInt("color1"),
                    color2 = obj.optInt("color2"),
                    accentColor = obj.optInt("accentColor")
                )
            } else null

            CachedTrack(
                track = LastFmTrack(
                    title = title,
                    artist = artist,
                    album = album,
                    trackUrl = trackUrl,
                    coverUrl = coverUrl,
                    isNowPlaying = isNowPlaying
                ),
                fetchedAt = fetchedAt,
                palette = palette
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse cached track for $key", e)
            null
        }
    }

    @Synchronized
    fun put(
        username: String,
        track: LastFmTrack,
        fetchedAt: Long = System.currentTimeMillis(),
        palette: LastFmPalette? = null
    ) {
        val key = username.trim().lowercase()
        if (key.isEmpty()) return
        try {
            val p = getPrefs()
            val existing = if (palette == null) get(key) else null
            val paletteToSave = palette ?: existing?.palette

            val obj = JSONObject().apply {
                put("title", track.title)
                put("artist", track.artist)
                put("album", track.album)
                put("trackUrl", track.trackUrl)
                if (!track.coverUrl.isNullOrEmpty()) {
                    put("coverUrl", track.coverUrl)
                }
                put("isNowPlaying", track.isNowPlaying)
                put("fetchedAt", fetchedAt)
                if (paletteToSave != null) {
                    put("color1", paletteToSave.color1)
                    put("color2", paletteToSave.color2)
                    put("accentColor", paletteToSave.accentColor)
                }
            }

            val editor = p.edit()
            val all = p.all
            if (all.size >= MAX_ENTRIES && !all.containsKey(key)) {
                evictOldest(all, editor)
            }
            editor.putString(key, obj.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache track for $key", e)
        }
    }

    @Synchronized
    fun putPalette(username: String, palette: LastFmPalette) {
        val key = username.trim().lowercase()
        if (key.isEmpty()) return
        val cached = get(key) ?: return
        put(key, cached.track, cached.fetchedAt, palette)
    }

    private fun evictOldest(all: Map<String, *>, editor: SharedPreferences.Editor) {
        try {
            val entries = mutableListOf<Pair<String, Long>>()
            for ((k, v) in all) {
                if (v is String) {
                    try {
                        val obj = JSONObject(v)
                        val fetchedAt = obj.optLong("fetchedAt", 0L)
                        entries.add(k to fetchedAt)
                    } catch (e: Exception) {
                        editor.remove(k)
                    }
                } else {
                    editor.remove(k)
                }
            }
            entries.sortBy { it.second }
            val removeCount = (all.size - MAX_ENTRIES + 1).coerceAtLeast(1)
            for (i in 0 until minOf(removeCount, entries.size)) {
                editor.remove(entries[i].first)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during cache eviction", e)
        }
    }
}
