package com.animarvle.app.data.mal

import com.animarvle.app.BuildConfig
import com.animarvle.app.domain.AiringStatus
import com.animarvle.app.domain.MediaUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * MyAnimeList API v2 client.
 * Connects directly to https://api.myanimelist.net/v2 using the client ID stored securely in .env.
 */
class MyAnimeListApi(
    private val client: OkHttpClient = defaultClient(),
    private val clientId: String = BuildConfig.MAL_CLIENT_ID,
) {

    suspend fun search(query: String, limit: Int = 20): List<MediaUi> = withContext(Dispatchers.IO) {
        if (clientId.isBlank() || query.isBlank()) return@withContext emptyList()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val fields = "id,title,main_picture,alternative_titles,start_date,end_date,synopsis,mean,rank,popularity,num_episodes,status,genres,media_type"
        val url = "$BASE_URL/anime?q=$encoded&limit=$limit&fields=$fields"
        fetchMediaList(url)
    }

    suspend fun topAiring(limit: Int = 20): List<MediaUi> = withContext(Dispatchers.IO) {
        ranking("airing", limit)
    }

    suspend fun allTimePopular(limit: Int = 20): List<MediaUi> = withContext(Dispatchers.IO) {
        ranking("bypopularity", limit)
    }

    suspend fun topRated(limit: Int = 20): List<MediaUi> = withContext(Dispatchers.IO) {
        ranking("all", limit)
    }

    suspend fun seasonal(year: Int, season: String, limit: Int = 20): List<MediaUi> = withContext(Dispatchers.IO) {
        if (clientId.isBlank()) return@withContext emptyList()
        val s = season.lowercase().trim()
        val fields = "id,title,main_picture,alternative_titles,start_date,synopsis,mean,num_episodes,status,genres,media_type"
        val url = "$BASE_URL/anime/season/$year/$s?limit=$limit&fields=$fields"
        fetchMediaList(url)
    }

    suspend fun ranking(rankingType: String = "all", limit: Int = 20): List<MediaUi> = withContext(Dispatchers.IO) {
        if (clientId.isBlank()) return@withContext emptyList()
        val fields = "id,title,main_picture,alternative_titles,start_date,synopsis,mean,num_episodes,status,genres,media_type"
        val url = "$BASE_URL/anime/ranking?ranking_type=$rankingType&limit=$limit&fields=$fields"
        fetchMediaList(url)
    }

    suspend fun details(id: Long): MediaUi? = withContext(Dispatchers.IO) {
        if (clientId.isBlank() || id <= 0) return@withContext null
        val fields = "id,title,main_picture,alternative_titles,start_date,end_date,synopsis,mean,rank,popularity,num_episodes,status,genres,media_type"
        val url = "$BASE_URL/anime/$id?fields=$fields"
        runCatching {
            val req = Request.Builder()
                .url(url)
                .header("X-MAL-CLIENT-ID", clientId)
                .header("User-Agent", "Animarvle/1.0")
                .header("Accept", "application/json")
                .build()

            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val body = response.body.string()
                val json = JSONObject(body)
                parseNodeToMediaUi(json)
            }
        }.getOrNull()
    }

    private fun fetchMediaList(url: String): List<MediaUi> {
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .header("X-MAL-CLIENT-ID", clientId)
                .header("User-Agent", "Animarvle/1.0")
                .header("Accept", "application/json")
                .build()

            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body.string()
                val json = JSONObject(body)
                val data = json.optJSONArray("data") ?: return emptyList()

                val results = mutableListOf<MediaUi>()
                for (i in 0 until data.length()) {
                    val obj = data.getJSONObject(i)
                    val node = obj.optJSONObject("node") ?: continue
                    parseNodeToMediaUi(node)?.let { results.add(it) }
                }
                results
            }
        }.getOrDefault(emptyList())
    }

    private fun parseNodeToMediaUi(node: JSONObject): MediaUi? {
        val id = node.optLong("id", -1L)
        if (id <= 0) return null
        val title = node.optString("title").takeIf { it.isNotBlank() } ?: return null

        val pictures = node.optJSONObject("main_picture")
        val coverUrl = pictures?.optString("large")?.takeIf { it.isNotBlank() }
            ?: pictures?.optString("medium")?.takeIf { it.isNotBlank() }

        val altTitles = node.optJSONObject("alternative_titles")
        val romajiTitle = altTitles?.optString("ja")?.takeIf { it.isNotBlank() }
        val englishTitle = altTitles?.optString("en")?.takeIf { it.isNotBlank() }
        val synonymsArray = altTitles?.optJSONArray("synonyms")
        val synonyms = mutableListOf<String>()
        if (synonymsArray != null) {
            for (i in 0 until synonymsArray.length()) {
                val s = synonymsArray.optString(i)
                if (s.isNotBlank()) synonyms.add(s)
            }
        }
        if (englishTitle != null && englishTitle != title && !synonyms.contains(englishTitle)) {
            synonyms.add(0, englishTitle)
        }

        val synopsis = node.optString("synopsis").takeIf { it.isNotBlank() }
        val meanScore = node.optDouble("mean", 0.0).takeIf { it > 0.0 }
        val startDate = node.optString("start_date").takeIf { it.isNotBlank() }
        val year = startDate?.take(4)?.toIntOrNull()
        val numEpisodes = node.optInt("num_episodes", 0).takeIf { it > 0 }
        val format = node.optString("media_type").uppercase().takeIf { it.isNotBlank() }

        val statusStr = node.optString("status").lowercase()
        val status = when (statusStr) {
            "finished_airing" -> AiringStatus.FINISHED
            "currently_airing" -> AiringStatus.RELEASING
            "not_yet_aired" -> AiringStatus.NOT_YET_RELEASED
            else -> AiringStatus.UNKNOWN
        }

        val genresArray = node.optJSONArray("genres")
        val genres = mutableListOf<String>()
        if (genresArray != null) {
            for (i in 0 until genresArray.length()) {
                val g = genresArray.optJSONObject(i)?.optString("name")
                if (!g.isNullOrBlank()) genres.add(g)
            }
        }

        return MediaUi(
            id = id,
            title = englishTitle ?: title,
            romajiTitle = romajiTitle ?: title,
            nativeTitle = romajiTitle,
            synonyms = synonyms,
            synopsis = synopsis,
            coverUrl = coverUrl,
            bannerUrl = coverUrl,
            genres = genres,
            score = meanScore,
            year = year,
            startDateIso = startDate,
            format = format,
            episodes = numEpisodes,
            durationMinutes = null,
            status = status,
            nextAiringEpisode = null,
            nextAiringAtEpochSec = null,
            seasons = emptyList(),
        )
    }

    companion object {
        const val BASE_URL = "https://api.myanimelist.net/v2"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
