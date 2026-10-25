package com.animarvle.app.data.simkl

import com.animarvle.app.BuildConfig
import com.animarvle.app.domain.AiringStatus
import com.animarvle.app.domain.EpisodeMetadata
import com.animarvle.app.domain.MediaUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class SimklIds(
    val simklId: Long,
    val tmdbId: Long? = null,
    val malId: Long? = null,
    val imdbId: String? = null,
    val slug: String? = null,
)

/**
 * Simkl API client.
 * Connects to https://api.simkl.com using the client ID stored securely in .env.
 * Provides fallback searching, cross-platform ID mapping (MAL, TMDB, IMDB, Simkl),
 * and rich episode metadata with thumbnails.
 */
class SimklApi(
    private val client: OkHttpClient = defaultClient(),
    private val clientId: String = BuildConfig.SIMKL_CLIENT_ID,
) {

    suspend fun searchAnime(query: String, limit: Int = 20): List<MediaUi> = withContext(Dispatchers.IO) {
        if (clientId.isBlank() || query.isBlank()) return@withContext emptyList()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$BASE_URL/search/anime?q=$encoded&limit=$limit&extended=full"
        fetchMediaList(url, isAnime = true)
    }

    suspend fun searchTv(query: String, limit: Int = 20): List<MediaUi> = withContext(Dispatchers.IO) {
        if (clientId.isBlank() || query.isBlank()) return@withContext emptyList()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$BASE_URL/search/tv?q=$encoded&limit=$limit&extended=full"
        fetchMediaList(url, isAnime = false)
    }

    suspend fun searchMovie(query: String, limit: Int = 20): List<MediaUi> = withContext(Dispatchers.IO) {
        if (clientId.isBlank() || query.isBlank()) return@withContext emptyList()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$BASE_URL/search/movie?q=$encoded&limit=$limit&extended=full"
        fetchMediaList(url, isAnime = false)
    }

    suspend fun searchMulti(query: String, limit: Int = 20): List<MediaUi> = withContext(Dispatchers.IO) {
        if (clientId.isBlank() || query.isBlank()) return@withContext emptyList()
        val tv = searchTv(query, limit / 2)
        val movies = searchMovie(query, limit / 2)
        (tv + movies).sortedByDescending { it.score ?: 0.0 }
    }

    suspend fun popularAnime(limit: Int = 20): List<MediaUi> = withContext(Dispatchers.IO) {
        if (clientId.isBlank()) return@withContext emptyList()
        val url = "$BASE_URL/anime/genres/all/popular-today?limit=$limit"
        fetchMediaList(url, isAnime = true)
    }

    suspend fun popularTv(limit: Int = 20): List<MediaUi> = withContext(Dispatchers.IO) {
        if (clientId.isBlank()) return@withContext emptyList()
        val url = "$BASE_URL/tv/genres/all/popular-today?limit=$limit"
        fetchMediaList(url, isAnime = false)
    }

    suspend fun popularMovies(limit: Int = 20): List<MediaUi> = withContext(Dispatchers.IO) {
        if (clientId.isBlank()) return@withContext emptyList()
        val url = "$BASE_URL/movies/genres/all/popular-today?limit=$limit"
        fetchMediaList(url, isAnime = false)
    }

    suspend fun findCrossIds(title: String, type: String? = null): SimklIds? = withContext(Dispatchers.IO) {
        if (clientId.isBlank() || title.isBlank()) return@withContext null
        val encoded = URLEncoder.encode(title.trim(), "UTF-8")
        val endpoint = when (type?.lowercase()) {
            "anime" -> "anime"
            "tv", "series" -> "tv"
            "movie", "movies" -> "movie"
            else -> "tv"
        }
        val url = "$BASE_URL/search/$endpoint?q=$encoded&limit=1&extended=full"
        val req = Request.Builder()
            .url(url)
            .header("simkl-api-key", clientId)
            .header("User-Agent", "Animarvle/1.0")
            .build()

        runCatching {
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val body = response.body.string()
                val arr = JSONArray(body)
                if (arr.length() == 0) return@runCatching null
                val obj = arr.getJSONObject(0)
                val idsObj = obj.optJSONObject("ids") ?: return@runCatching null
                val simklId = idsObj.optLong("simkl_id", 0L).takeIf { it > 0 } ?: return@runCatching null
                val tmdbId = idsObj.optString("tmdb").toLongOrNull()
                val malId = idsObj.optString("mal").toLongOrNull()
                val imdbId = idsObj.optString("imdb").takeIf { it.isNotBlank() }
                val slug = idsObj.optString("slug").takeIf { it.isNotBlank() }
                SimklIds(simklId = simklId, tmdbId = tmdbId, malId = malId, imdbId = imdbId, slug = slug)
            }
        }.getOrNull()
    }

    suspend fun findTmdbIdByTitle(title: String, isTv: Boolean): Long? = withContext(Dispatchers.IO) {
        val cross = findCrossIds(title, if (isTv) "tv" else "movie")
        cross?.tmdbId
    }

    suspend fun fetchEpisodes(
        simklId: Long,
        isTv: Boolean,
        seasonNumber: Int = 1,
    ): Map<Int, EpisodeMetadata> = withContext(Dispatchers.IO) {
        if (clientId.isBlank() || simklId <= 0) return@withContext emptyMap()
        val endpoint = if (isTv) "tv" else "anime"
        val url = "$BASE_URL/$endpoint/episodes/$simklId"
        val req = Request.Builder()
            .url(url)
            .header("simkl-api-key", clientId)
            .header("User-Agent", "Animarvle/1.0")
            .build()

        runCatching {
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return@runCatching emptyMap()
                val body = response.body.string()
                val arr = JSONArray(body)
                val map = mutableMapOf<Int, EpisodeMetadata>()

                for (i in 0 until arr.length()) {
                    val ep = arr.getJSONObject(i)
                    val epSeason = ep.optInt("season", ep.optJSONObject("tvdb")?.optInt("season", 1) ?: 1)
                    val epNum = ep.optInt("episode", 0).takeIf { it > 0 } ?: continue

                    // For TV shows, filter to the requested season.
                    // For anime, if seasonNumber > 1, check if season matches, else treat absolute epNum.
                    if (isTv && epSeason != seasonNumber) continue
                    if (!isTv && seasonNumber > 1 && epSeason != seasonNumber) continue

                    val epTitle = ep.optString("title").takeIf { it.isNotBlank() }
                    val desc = ep.optString("description").takeIf { it.isNotBlank() }
                    val img = ep.optString("img").takeIf { it.isNotBlank() }
                    val thumbUrl = img?.let { "https://simkl.in/episodes/${it}_w.jpg" }

                    map[epNum] = EpisodeMetadata(
                        episodeNumber = epNum,
                        title = epTitle,
                        overview = desc,
                        thumbnailUrl = thumbUrl,
                    )
                }
                map
            }
        }.getOrDefault(emptyMap())
    }

    suspend fun fetchEpisodesByTitle(
        title: String,
        isTv: Boolean,
        seasonNumber: Int = 1,
    ): Map<Int, EpisodeMetadata> = withContext(Dispatchers.IO) {
        val cross = findCrossIds(title, if (isTv) "tv" else "anime") ?: return@withContext emptyMap()
        fetchEpisodes(cross.simklId, isTv, seasonNumber)
    }

    private fun fetchMediaList(url: String, isAnime: Boolean): List<MediaUi> {
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .header("simkl-api-key", clientId)
                .header("User-Agent", "Animarvle/1.0")
                .header("Accept", "application/json")
                .build()

            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body.string()
                val arr = JSONArray(body)
                val results = mutableListOf<MediaUi>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    parseItemToMediaUi(obj, isAnime)?.let { results.add(it) }
                }
                results
            }
        }.getOrDefault(emptyList())
    }

    private fun parseItemToMediaUi(obj: JSONObject, isAnime: Boolean): MediaUi? {
        val ids = obj.optJSONObject("ids")
        // Prefer TMDB ID if present, else fallback to simkl_id
        val simklId = ids?.optLong("simkl_id", 0L) ?: obj.optLong("simkl_id", 0L)
        val tmdbId = ids?.optString("tmdb")?.toLongOrNull()
        val malId = ids?.optString("mal")?.toLongOrNull()
        val id = tmdbId ?: malId ?: (if (simklId > 0) simklId else return null)

        val title = obj.optString("title").takeIf { it.isNotBlank() } ?: return null
        val romajiTitle = obj.optString("title_romaji").takeIf { it.isNotBlank() }
        val poster = obj.optString("poster").takeIf { it.isNotBlank() }
        val fanart = obj.optString("fanart").takeIf { it.isNotBlank() }

        val coverUrl = poster?.let { "https://simkl.in/posters/${it}_m.jpg" }
        val bannerUrl = fanart?.let { "https://simkl.in/fanart/${it}_medium.jpg" } ?: coverUrl

        val year = obj.optInt("year", 0).takeIf { it > 0 }
        val synopsis = obj.optString("overview").takeIf { it.isNotBlank() }

        val ratings = obj.optJSONObject("ratings")
        val score = ratings?.optJSONObject("simkl")?.optDouble("rating")
            ?: ratings?.optJSONObject("imdb")?.optDouble("rating")
            ?: ratings?.optJSONObject("mal")?.optDouble("rating")

        val statusStr = obj.optString("status").lowercase()
        val status = when (statusStr) {
            "ended", "finished" -> AiringStatus.FINISHED
            "airing" -> AiringStatus.RELEASING
            "upcoming" -> AiringStatus.NOT_YET_RELEASED
            else -> AiringStatus.UNKNOWN
        }

        val epCount = obj.optInt("ep_count", obj.optInt("total_episodes", 0)).takeIf { it > 0 }
        val format = obj.optString("type", obj.optString("anime_type", if (isAnime) "ANIME" else "TV")).uppercase()

        val genresArray = obj.optJSONArray("genres")
        val genres = mutableListOf<String>()
        if (genresArray != null) {
            for (i in 0 until genresArray.length()) {
                val g = genresArray.optString(i)
                if (g.isNotBlank()) genres.add(g)
            }
        }

        return MediaUi(
            id = id,
            title = title,
            romajiTitle = romajiTitle ?: title,
            nativeTitle = romajiTitle,
            synonyms = emptyList(),
            synopsis = synopsis,
            coverUrl = coverUrl,
            bannerUrl = bannerUrl,
            genres = genres,
            score = score,
            year = year,
            startDateIso = obj.optString("first_aired").takeIf { it.isNotBlank() },
            format = format,
            episodes = epCount,
            durationMinutes = obj.optInt("runtime", 0).takeIf { it > 0 },
            status = status,
            nextAiringEpisode = null,
            nextAiringAtEpochSec = null,
            seasons = emptyList(),
            isTmdb = !isAnime,
        )
    }

    companion object {
        const val BASE_URL = "https://api.simkl.com"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
