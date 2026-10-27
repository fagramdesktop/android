package xie.fa.gram.helpers.lastfm

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject
import org.telegram.messenger.ApplicationLoader

sealed class CoverCacheResult {
    data class Hit(val url: String) : CoverCacheResult()
    object Negative : CoverCacheResult()
    object Miss : CoverCacheResult()
}

object LastFmCoverCache {
    private const val TAG = "LastFmCoverCache"
    private const val PREFS_FILE = "lastfm_cover_cache"
    private const val POSITIVE_TTL_MS = 7L * 24 * 60 * 60 * 1000 // 7 days
    private const val NEGATIVE_TTL_MS = 24L * 60 * 60 * 1000     // 24 hours
    private const val MAX_ENTRIES = 500

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

    fun normalizeKey(artist: String, track: String): String {
        val a = artist.trim().lowercase().replace(Regex("\\s+"), " ")
        val t = track.trim().lowercase().replace(Regex("\\s+"), " ")
        if (a.isEmpty() || t.isEmpty() ||
            a.equals("unknown artist", ignoreCase = true) ||
            t.equals("unknown track", ignoreCase = true)
        ) {
            return ""
        }
        return "$a|$t"
    }

    @Synchronized
    fun get(artist: String, track: String): CoverCacheResult {
        val key = normalizeKey(artist, track)
        if (key.isEmpty()) return CoverCacheResult.Negative
        val p = getPrefs()
        val jsonString = p.getString(key, null) ?: return CoverCacheResult.Miss
        return try {
            val obj = JSONObject(jsonString)
            val url = if (obj.has("url") && !obj.isNull("url")) obj.optString("url", "").trim() else ""
            val timestamp = obj.optLong("timestamp", 0L)
            val now = System.currentTimeMillis()
            val age = now - timestamp
            if (url.isNotEmpty()) {
                if (age in 0..POSITIVE_TTL_MS) {
                    CoverCacheResult.Hit(url)
                } else {
                    CoverCacheResult.Miss
                }
            } else {
                if (age in 0..NEGATIVE_TTL_MS) {
                    CoverCacheResult.Negative
                } else {
                    CoverCacheResult.Miss
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse cover cache entry for $key", e)
            CoverCacheResult.Miss
        }
    }

    @Synchronized
    fun put(artist: String, track: String, url: String?) {
        val key = normalizeKey(artist, track)
        if (key.isEmpty()) return
        try {
            val p = getPrefs()
            pruneIfNeeded(p)
            val obj = JSONObject().apply {
                if (!url.isNullOrEmpty()) {
                    put("url", url.trim())
                } else {
                    put("url", JSONObject.NULL)
                }
                put("timestamp", System.currentTimeMillis())
            }
            p.edit().putString(key, obj.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write cover cache entry for $key", e)
        }
    }

    private fun pruneIfNeeded(p: SharedPreferences) {
        val all = p.all
        if (all.size < MAX_ENTRIES) return
        try {
            val entries = mutableListOf<Pair<String, Long>>()
            for ((k, v) in all) {
                if (v is String) {
                    try {
                        val ts = JSONObject(v).optLong("timestamp", 0L)
                        entries.add(k to ts)
                    } catch (_: Exception) {
                        entries.add(k to 0L)
                    }
                }
            }
            entries.sortBy { it.second }
            val toRemove = entries.take(entries.size - MAX_ENTRIES / 2)
            val editor = p.edit()
            for ((k, _) in toRemove) {
                editor.remove(k)
            }
            editor.apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error pruning cover cache", e)
        }
    }
}
