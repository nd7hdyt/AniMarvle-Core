package com.animarvle.app.data.source.builtin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared AniZip client (https://ani.zip) for resolving AniList IDs to MAL IDs
 * and fetching canonical episode titles/metadata, identical to Mizuki's AniZipService.
 */
object AniZipHelper {

    data class AniZipResult(
        val malId: Int?,
        val anilistId: Int?,
        val tmdbId: Long? = null,
        val titles: Map<String, String>,
        val episodeCount: Int,
        val specialCount: Int,
        val episodes: Map<Int, com.animarvle.app.domain.EpisodeMetadata> = emptyMap(),
        val episodesBySeason: Map<Int, Map<Int, com.animarvle.app.domain.EpisodeMetadata>> = emptyMap(),
    )

    private val cache = ConcurrentHashMap<Int, AniZipResult>()

    suspend fun getEpisodeMetadata(http: OkHttpClient, anilistId: Int, seasonNumber: Int = 1): Map<Int, com.animarvle.app.domain.EpisodeMetadata> {
        val mappings = getMappings(http, anilistId) ?: return emptyMap()
        if (mappings.episodesBySeason.isNotEmpty()) {
            val seasonEps = mappings.episodesBySeason[seasonNumber]
            if (!seasonEps.isNullOrEmpty()) return seasonEps
        }
        return mappings.episodes
    }

    suspend fun getMappings(http: OkHttpClient, anilistId: Int): AniZipResult? = withContext(Dispatchers.IO) {
        if (anilistId <= 0) return@withContext null
        cache[anilistId]?.let { return@withContext it }

        try {
            val url = "https://api.ani.zip/mappings?anilist_id=$anilistId"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Animarvle/1.0 (Android)")
                .build()

            val res = http.newCall(req).execute()
            if (!res.isSuccessful) return@withContext null
            val body = res.body.string()
            if (body.isBlank()) return@withContext null

            val json = JSONObject(body)
            val mappings = json.optJSONObject("mappings")
            val malId = mappings?.optInt("mal_id", 0)?.takeIf { it > 0 }
            val tmdbId = mappings?.optString("themoviedb_id")?.toLongOrNull()
                ?: mappings?.optLong("themoviedb_id", 0L)?.takeIf { it > 0L }
            val epCount = json.optInt("episodeCount", 0)
            val specCount = json.optInt("specialCount", 0)

            val titlesMap = mutableMapOf<String, String>()
            json.optJSONObject("titles")?.let { titlesObj ->
                val keys = titlesObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = titlesObj.optString(k, "")
                    if (v.isNotBlank()) titlesMap[k] = v
                }
            }

            val epMap = mutableMapOf<Int, com.animarvle.app.domain.EpisodeMetadata>()
            val bySeasonMap = mutableMapOf<Int, MutableMap<Int, com.animarvle.app.domain.EpisodeMetadata>>()
            json.optJSONObject("episodes")?.let { episodesObj ->
                val keys = episodesObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val absEpNum = k.toIntOrNull() ?: continue
                    val epObj = episodesObj.optJSONObject(k) ?: continue
                    val sNum = epObj.optInt("seasonNumber", 1).takeIf { it > 0 } ?: 1
                    val epInSeason = epObj.optInt("episodeNumber", absEpNum).takeIf { it > 0 } ?: absEpNum
                    val img = epObj.optString("image").takeIf { it.isNotBlank() }
                    val overview = epObj.optString("overview").takeIf { it.isNotBlank() }
                    val titleStr = when (val t = epObj.opt("title")) {
                        is JSONObject -> t.optString("en").takeIf { it.isNotBlank() }
                            ?: t.optString("x-jat").takeIf { it.isNotBlank() }
                            ?: t.optString("ja").takeIf { it.isNotBlank() }
                        is String -> t.takeIf { it.isNotBlank() }
                        null -> null
                        else -> null
                    }
                    val item = com.animarvle.app.domain.EpisodeMetadata(
                        episodeNumber = absEpNum,
                        title = titleStr,
                        overview = overview,
                        thumbnailUrl = img,
                    )
                    epMap[absEpNum] = item
                    val inSeasonItem = item.copy(episodeNumber = epInSeason)
                    bySeasonMap.getOrPut(sNum) { mutableMapOf() }[epInSeason] = inSeasonItem
                }
            }

            val result = AniZipResult(
                malId = malId,
                anilistId = anilistId,
                tmdbId = tmdbId,
                titles = titlesMap,
                episodeCount = epCount,
                specialCount = specCount,
                episodes = epMap,
                episodesBySeason = if (bySeasonMap.size > 1) bySeasonMap else emptyMap(),
            )
            cache[anilistId] = result
            result
        } catch (_: Exception) {
            null
        }
    }
}
