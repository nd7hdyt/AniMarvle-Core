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
        val titles: Map<String, String>,
        val episodeCount: Int,
        val specialCount: Int,
    )

    private val cache = ConcurrentHashMap<Int, AniZipResult>()

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

            val result = AniZipResult(
                malId = malId,
                anilistId = anilistId,
                titles = titlesMap,
                episodeCount = epCount,
                specialCount = specCount,
            )
            cache[anilistId] = result
            result
        } catch (_: Exception) {
            null
        }
    }
}
