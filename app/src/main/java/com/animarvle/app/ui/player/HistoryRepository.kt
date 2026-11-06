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
    val streamUrl: String? = null,
    val streamHeaders: Map<String, String> = emptyMap(),
    val serverName: String? = null,
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
                val posterUrl = if (o.has("posterUrl") && !o.isNull("posterUrl")) o.getString("posterUrl") else null
                val durationMs = o.optLong("durationMs", 0L)
                val sanitizedType = sanitizeMediaType(rawMediaType, title, mediaUrl, durationMs, posterUrl)
                if (sanitizedType != rawMediaType) {
                    modified = true
                }
                val streamUrl = if (o.has("streamUrl") && !o.isNull("streamUrl")) o.getString("streamUrl") else null
                val serverName = if (o.has("serverName") && !o.isNull("serverName")) o.getString("serverName") else null
                val streamHeaders = mutableMapOf<String, String>()
                if (o.has("streamHeaders") && !o.isNull("streamHeaders")) {
                    val hObj = o.optJSONObject("streamHeaders")
                    if (hObj != null) {
                        val it = hObj.keys()
                        while (it.hasNext()) {
                            val k = it.next()
                            streamHeaders[k] = hObj.optString(k, "")
                        }
                    }
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
                    streamUrl = streamUrl,
                    streamHeaders = streamHeaders,
                    serverName = serverName,
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

    fun getSavedStream(title: String, episodeNumber: Int = 1): HistoryItem? {
        val clean = title.trim()
        return _historyFlow.value.firstOrNull {
            it.title.trim().equals(clean, ignoreCase = true) &&
                it.episodeNumber == episodeNumber &&
                !it.streamUrl.isNullOrBlank()
        } ?: _historyFlow.value.firstOrNull {
            it.title.trim().equals(clean, ignoreCase = true) &&
                !it.streamUrl.isNullOrBlank()
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
        streamUrl: String? = null,
        streamHeaders: Map<String, String> = emptyMap(),
        serverName: String? = null,
    ) = withContext(Dispatchers.IO) {
        if (durationMs <= 0 || positionMs <= 0) return@withContext
        val sanitizedType = sanitizeMediaType(mediaType, title, mediaUrl, durationMs, posterUrl)
        val current = _historyFlow.value.toMutableList()
        val existing = current.firstOrNull {
            it.mediaUrl == mediaUrl || it.title.trim().equals(title.trim(), ignoreCase = true)
        }
        val effectiveStreamUrl = streamUrl ?: existing?.streamUrl
        val effectiveHeaders = if (streamHeaders.isNotEmpty()) streamHeaders else (existing?.streamHeaders ?: emptyMap())
        val effectiveServerName = serverName ?: existing?.serverName
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
                streamUrl = effectiveStreamUrl,
                streamHeaders = effectiveHeaders,
                serverName = effectiveServerName,
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
                    if (!item.streamUrl.isNullOrBlank()) {
                        put("streamUrl", item.streamUrl)
                    }
                    if (!item.serverName.isNullOrBlank()) {
                        put("serverName", item.serverName)
                    }
                    if (item.streamHeaders.isNotEmpty()) {
                        val hObj = JSONObject()
                        item.streamHeaders.forEach { (k, v) -> hObj.put(k, v) }
                        put("streamHeaders", hObj)
                    }
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

        private val KNOWN_ANIME_KEYWORDS = setOf(
            "the beginning after the end", "eminence in shadow", "dungeon",
            "danmachi", "seven mortal sins", "bikini warriors", "kimi omou koi",
            "what she fell on", "adam's sweet agony", "araiya", "overflow",
            "euphoria", "mankitsu", "resort boin", "boku no pico", "eroge",
            "fella pure", "fela pure", "mitarashi", "kaihatsu zanmai", "jujutsu kaisen",
            "demon slayer", "attack on titan", "naruto", "one piece", "bleach",
            "my hero academia", "chainsaw man", "solo leveling", "black clover",
            "sword art online", "death note", "fullmetal alchemist", "hunter x hunter",
            "tokyo ghoul", "re:zero", "konosuba", "mushoku tensei", "overlord",
            "slime datta ken", "blue lock", "haikyuu", "vinland saga", "spy x family",
            "fate/stay", "fate/zero", "classroom of the elite", "mob psycho",
            "hell's paradise", "kaiju no. 8", "oshi no ko", "frieren", "dandadan"
        )

        fun isAnimeTitle(title: String?): Boolean {
            if (title.isNullOrBlank()) return false
            val lower = title.trim().lowercase(Locale.ROOT)
            return KNOWN_ANIME_KEYWORDS.any { lower.contains(it) }
        }

        fun sanitizeMediaType(
            rawMediaType: String?,
            title: String?,
            mediaUrl: String?,
            durationMs: Long = 0L,
            posterUrl: String? = null,
        ): String {
            if (title != null && com.animarvle.app.AnimarvleApp.isDonghua(title)) return "ANIME"

            val lowerTitle = title.orEmpty().trim().lowercase(Locale.ROOT)
            val lowerUrl = mediaUrl.orEmpty().trim().lowercase(Locale.ROOT)
            val lowerPoster = posterUrl.orEmpty().trim().lowercase(Locale.ROOT)

            // 1. Posters from AniList, MyAnimeList, Kitsu, or adult anime hosts are 100% ANIME
            if (lowerPoster.contains("anilist.co") || lowerPoster.contains("myanimelist.net") ||
                lowerPoster.contains("kitsu.io") || lowerPoster.contains("hentaimama") ||
                lowerPoster.contains("hentaini") || lowerPoster.contains("animesex") ||
                lowerPoster.contains("yesterdaymail") || lowerPoster.contains("gdvid")
            ) {
                return "ANIME"
            }

            // 2. Anime & adult anime titles are ALWAYS ANIME
            if (isAnimeTitle(lowerTitle)) {
                return "ANIME"
            }

            // 3. Known adult / anime domains or streaming URLs are ALWAYS ANIME
            if (lowerUrl.contains("hentaini") || lowerUrl.contains("yesterdaymail") ||
                lowerUrl.contains("hentaimama") || lowerUrl.contains("gdvid") ||
                lowerUrl.contains("animesex") || lowerUrl.contains("clean-tube-player") ||
                lowerUrl.contains("povblowjob") || lowerUrl.contains("anitaku") ||
                lowerUrl.contains("gogoanime") || lowerUrl.contains("hianime") ||
                lowerUrl.contains("zoro") || lowerUrl.contains("type=anime")
            ) {
                return "ANIME"
            }

            val knownTvTitles = setOf(
                "peaky blinders", "the simpsons", "breaking bad", "mobland",
                "lupin", "the terminal list", "game of thrones", "stranger things",
                "the boys", "house of the dragon", "better call saul", "severance"
            )
            val knownMovieTitles = setOf(
                "masterplan", "swapped", "the cycle", "foursome",
                "spider-man: brand new day", "avatar aang: the last airbender",
                "prey", "hope"
            )

            if (knownTvTitles.contains(lowerTitle)) return "TV"
            if (knownMovieTitles.contains(lowerTitle)) return "MOVIE"

            // Asian Cinema (Cat3Movie, JavMost, jav.com.co, JavMenu, Pinoy, or adult live-action movies/studio codes)
            val mediaIdLong = mediaUrl?.toLongOrNull()
            if (mediaIdLong != null && mediaIdLong >= 800000000L) {
                return "MOVIE"
            }

            if (lowerUrl.contains("cat3movie") || lowerPoster.contains("cat3movie") ||
                lowerUrl.contains("javmost") || lowerPoster.contains("javmost") ||
                lowerUrl.contains("jav.com.co") || lowerPoster.contains("jav.com.co") ||
                lowerUrl.contains("javmenu") || lowerPoster.contains("javmenu") ||
                lowerUrl.contains("sooplive") || lowerUrl.contains("mostplayer") || lowerUrl.contains("dooplayer") ||
                lowerUrl.contains("pinoy") || lowerUrl.contains("vivamax") || lowerTitle.contains("vivamax") ||
                com.animarvle.app.AnimarvleApp.isAsianAdultTitle(title) ||
                Regex("""\b[a-z]{2,6}[-_]?\d{2,5}\b""").containsMatchIn(lowerTitle)
            ) {
                return "MOVIE"
            }

            // Pure numeric mediaUrl indicates a TMDB ID (e.g. "61595", "212488", "220360")
            if (mediaUrl?.toLongOrNull() != null) {
                return if (durationMs > 50 * 60 * 1000L) "MOVIE" else "TV"
            }

            if (lowerUrl.contains("type=movie") && (lowerUrl.contains("tmdb=") || lowerUrl.contains("/movie/"))) return "MOVIE"
            if (lowerUrl.contains("type=tv") && (lowerUrl.contains("tmdb=") || lowerUrl.contains("/tv/"))) return "TV"

            val raw = rawMediaType.orEmpty()
            if (raw.equals("MOVIE", ignoreCase = true) || raw.equals("TV", ignoreCase = true)) {
                return raw.uppercase(Locale.ROOT)
            }

            return "ANIME"
        }
    }
}
