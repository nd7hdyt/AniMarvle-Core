package com.animarvle.app.data.source.builtin

import com.animarvle.sourceapi.SkipTimes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Shared AniSkip client (https://aniskip.com) providing opening and ending
 * skip intervals for anime episodes, matching the native experience in Mizuki.
 */
object AniSkipHelper {

    suspend fun getSkipTimes(
        http: OkHttpClient,
        malId: Int?,
        episodeNumber: Int,
        episodeLengthSeconds: Double = 0.0,
    ): SkipTimes? = withContext(Dispatchers.IO) {
        if (malId == null || malId <= 0 || episodeNumber <= 0) return@withContext null
        try {
            val url = "https://api.aniskip.com/v2/skip-times/$malId/$episodeNumber?types[]=op&types[]=ed&episodeLength=$episodeLengthSeconds"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Animarvle/1.0 (Android; Media3)")
                .build()

            val response = http.newCall(req).execute()
            if (!response.isSuccessful) return@withContext null
            val body = response.body.string()
            if (body.isBlank()) return@withContext null

            val json = JSONObject(body)
            if (!json.optBoolean("found", false)) return@withContext null
            val results = json.optJSONArray("results") ?: return@withContext null

            var openingRange: LongRange? = null
            var endingRange: LongRange? = null

            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val skipType = item.optString("skipType", "").lowercase()
                val interval = item.optJSONObject("interval") ?: continue
                val startMs = (interval.optDouble("startTime", 0.0) * 1000).toLong()
                val endMs = (interval.optDouble("endTime", 0.0) * 1000).toLong()

                if (startMs in 0..endMs) {
                    when (skipType) {
                        "op" -> openingRange = (startMs..endMs)
                        "ed" -> endingRange = (startMs..endMs)
                    }
                }
            }

            if (openingRange != null || endingRange != null) {
                SkipTimes(opening = openingRange, ending = endingRange)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
