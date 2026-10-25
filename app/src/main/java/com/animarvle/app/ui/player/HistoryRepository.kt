package com.animarvle.app.ui.player

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class HistoryItem(
    val mediaUrl: String,
    val title: String,
    val posterUrl: String?,
    val episodeNumber: Int,
    val positionMs: Long,
    val durationMs: Long,
    val audio: String,
    val updatedAtMs: Long,
    val mediaType: String = "ANIME",
    val seasonNumber: Int = 1,
) {
    val progressPercent: Int
        get() = if (durationMs > 0) ((positionMs.toDouble() / durationMs) * 100).toInt() else 0
}

class HistoryRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _historyFlow = MutableStateFlow<List<HistoryItem>>(emptyList())
    val historyFlow: StateFlow<List<HistoryItem>> = _historyFlow.asStateFlow()

    init {
        load()
    }

    private fun load() {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return
        runCatching {
            val arr = JSONArray(raw)
            var modified = false
            val list = (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val rawMediaType = o.optString("mediaType", "ANIME")
                val mediaUrl = o.getString("mediaUrl")
                val title = o.getString("title")
                val durationMs = o.optLong("durationMs", 0L)
                val sanitizedType = sanitizeMediaType(rawMediaType, title, mediaUrl, durationMs)
                if (sanitizedType != rawMediaType) {
                    modified = true
                }
                HistoryItem(
                    mediaUrl = mediaUrl,
                    title = title,
                    posterUrl = if (o.has("posterUrl") && !o.isNull("posterUrl")) o.getString("posterUrl") else null,
                    episodeNumber = o.optInt("episodeNumber", 1),
                    positionMs = o.optLong("positionMs", 0L),
                    durationMs = durationMs,
                    audio = o.optString("audio", "SUB"),
                    updatedAtMs = o.optLong("updatedAtMs", System.currentTimeMillis()),
                    mediaType = sanitizedType,
                    seasonNumber = o.optInt("seasonNumber", 1),
                )
            }
            // Deduplicate existing entries by title (keep the most recently updated one)
            val deduplicated = list
                .sortedByDescending { it.updatedAtMs }
                .distinctBy { it.title.trim().lowercase(Locale.ROOT) }
            _historyFlow.value = deduplicated

            if (modified) {
                val updatedArr = JSONArray()
                deduplicated.forEach { item ->
                    updatedArr.put(
                        JSONObject().apply {
                            put("mediaUrl", item.mediaUrl)
                            put("title", item.title)
                            put("posterUrl", item.posterUrl ?: JSONObject.NULL)
                            put("episodeNumber", item.episodeNumber)
                            put("positionMs", item.positionMs)
                            put("durationMs", item.durationMs)
                            put("audio", item.audio)
                            put("updatedAtMs", item.updatedAtMs)
                            put("mediaType", item.mediaType)
                            put("seasonNumber", item.seasonNumber)
                        },
                    )
                }
                prefs.edit().putString(KEY_ITEMS, updatedArr.toString()).apply()
            }
        }
    }

    suspend fun saveProgress(
        mediaUrl: String,
        title: String,
        posterUrl: String?,
        episodeNumber: Int,
        positionMs: Long,
        durationMs: Long,
        audio: String,
        mediaType: String = "ANIME",
        seasonNumber: Int = 1,
    ) = withContext(Dispatchers.IO) {
        if (durationMs <= 0 || positionMs <= 0) return@withContext
        val sanitizedType = sanitizeMediaType(mediaType, title, mediaUrl, durationMs)
        val current = _historyFlow.value.toMutableList()
        // Deduplicate and update in-place: if title matches (or mediaUrl matches), remove previous entry
        current.removeAll {
            it.mediaUrl == mediaUrl || it.title.trim().equals(title.trim(), ignoreCase = true)
        }
        current.add(
            0,
            HistoryItem(
                mediaUrl = mediaUrl,
                title = title,
                posterUrl = posterUrl,
                episodeNumber = episodeNumber,
                positionMs = positionMs,
                durationMs = durationMs,
                audio = audio,
                updatedAtMs = System.currentTimeMillis(),
                mediaType = sanitizedType,
                seasonNumber = seasonNumber,
            ),
        )
        val trimmed = current.take(MAX_HISTORY_ENTRIES)
        val arr = JSONArray()
        trimmed.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("mediaUrl", item.mediaUrl)
                    put("title", item.title)
                    put("posterUrl", item.posterUrl ?: JSONObject.NULL)
                    put("episodeNumber", item.episodeNumber)
                    put("positionMs", item.positionMs)
                    put("durationMs", item.durationMs)
                    put("audio", item.audio)
                    put("updatedAtMs", item.updatedAtMs)
                    put("mediaType", item.mediaType)
                    put("seasonNumber", item.seasonNumber)
                },
            )
        }
        prefs.edit().putString(KEY_ITEMS, arr.toString()).apply()
        _historyFlow.value = trimmed
    }

    suspend fun remove(mediaUrl: String) = withContext(Dispatchers.IO) {
        val current = _historyFlow.value.toMutableList()
        if (current.removeAll { it.mediaUrl == mediaUrl }) {
            val arr = JSONArray()
            current.forEach { item ->
                arr.put(
                    JSONObject().apply {
                        put("mediaUrl", item.mediaUrl)
                        put("title", item.title)
                        put("posterUrl", item.posterUrl ?: JSONObject.NULL)
                        put("episodeNumber", item.episodeNumber)
                        put("positionMs", item.positionMs)
                        put("durationMs", item.durationMs)
                        put("audio", item.audio)
                        put("updatedAtMs", item.updatedAtMs)
                        put("mediaType", item.mediaType)
                    },
                )
            }
            prefs.edit().putString(KEY_ITEMS, arr.toString()).apply()
            _historyFlow.value = current
        }
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        prefs.edit().remove(KEY_ITEMS).apply()
        _historyFlow.value = emptyList()
    }

    suspend fun clear() = clearAll()

    suspend fun restoreHistory(items: List<HistoryItem>) = withContext(Dispatchers.IO) {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("mediaUrl", item.mediaUrl)
                    put("title", item.title)
                    put("posterUrl", item.posterUrl ?: JSONObject.NULL)
                    put("episodeNumber", item.episodeNumber)
                    put("positionMs", item.positionMs)
                    put("durationMs", item.durationMs)
                    put("audio", item.audio)
                    put("updatedAtMs", item.updatedAtMs)
                    put("mediaType", item.mediaType)
                },
            )
        }
        prefs.edit().putString(KEY_ITEMS, arr.toString()).apply()
        _historyFlow.value = items
    }

    companion object {
        const val PREFS_NAME = "animarvel_history"
        const val KEY_ITEMS = "history_entries"
        const val MAX_HISTORY_ENTRIES = 20

        fun sanitizeMediaType(
            rawMediaType: String,
            title: String,
            mediaUrl: String,
            durationMs: Long = 0L,
        ): String {
            if (com.animarvle.app.AnimarvleApp.isDonghua(title)) return "ANIME"

            val lowerTitle = title.trim().lowercase(Locale.ROOT)
            val knownTvTitles = setOf(
                "peaky blinders", "the simpsons", "breaking bad", "mobland",
                "lupin", "the terminal list", "game of thrones", "stranger things",
                "the boys", "house of the dragon", "better call saul", "severance"
            )
            val knownMovieTitles = setOf(
                "masterplan", "swapped", "the cycle", "foursome",
                "spider-man: brand new day", "avatar aang: the last airbender"
            )

            if (knownTvTitles.contains(lowerTitle)) return "TV"
            if (knownMovieTitles.contains(lowerTitle)) return "MOVIE"

            // Pure numeric mediaUrl indicates a TMDB ID (e.g. "61595", "212488", "220360")
            if (mediaUrl.toLongOrNull() != null) {
                return if (durationMs > 50 * 60 * 1000L) "MOVIE" else "TV"
            }

            if (mediaUrl.contains("type=movie")) return "MOVIE"
            if (mediaUrl.contains("type=tv")) {
                if (rawMediaType.equals("ANIME", ignoreCase = true)) {
                    val isAnimeTitle = lowerTitle.contains("season") ||
                        lowerTitle.contains("student council") ||
                        lowerTitle.contains("black clover") ||
                        lowerTitle.contains("apothecary") ||
                        lowerTitle.contains("dan da dan") ||
                        lowerTitle.contains("edgerunners")
                    if (isAnimeTitle) return "ANIME"
                }
                return "TV"
            }

            if (rawMediaType.equals("MOVIE", ignoreCase = true) || rawMediaType.equals("TV", ignoreCase = true)) {
                return rawMediaType.uppercase(Locale.ROOT)
            }

            return "ANIME"
        }
    }
}
