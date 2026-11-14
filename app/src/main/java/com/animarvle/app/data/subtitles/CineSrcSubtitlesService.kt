package com.animarvle.app.data.subtitles

import android.util.Log
import com.animarvle.sourceapi.SubFormat
import com.animarvle.sourceapi.SubtitleTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object CineSrcSubtitlesService {
    private const val TAG = "CineSrcSubtitles"
    private const val API_URL = "https://cinesrc.st/api/subtitles/search"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val internalClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    suspend fun fetchSubtitles(
        client: OkHttpClient? = null,
        tmdbId: Long,
        isMovie: Boolean,
        season: Int = 1,
        episode: Int = 1,
    ): List<SubtitleTrack> = withContext(Dispatchers.IO) {
        if (tmdbId <= 0) return@withContext emptyList()

        val jsonPayload = JSONObject().apply {
            put("tmdbId", tmdbId)
            if (isMovie) {
                put("type", "movie")
            } else {
                put("type", "tv")
                put("season", season.coerceAtLeast(1))
                put("episode", episode.coerceAtLeast(1))
            }
        }.toString()

        val request = Request.Builder()
            .url(API_URL)
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .post(jsonPayload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val httpClient = client ?: internalClient
        val result = mutableListOf<SubtitleTrack>()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Subtitles query failed: HTTP ${response.code} for tmdbId=$tmdbId")
                    return@withContext emptyList()
                }

                val bodyStr = response.body?.string() ?: return@withContext emptyList()
                val root = JSONObject(bodyStr)
                val subsArray = root.optJSONArray("subtitles") ?: return@withContext emptyList()

                for (i in 0 until subsArray.length()) {
                    val sub = subsArray.optJSONObject(i) ?: continue
                    val rawUrl = sub.optString("url").trim()
                    if (rawUrl.isBlank()) continue

                    // Enforce UTF-8 encoding parameter on the subtitle URL
                    val cleanUrl = if (rawUrl.contains("encoding=")) {
                        rawUrl.replace(Regex("encoding=[^&]+"), "encoding=UTF-8")
                    } else {
                        val sep = if (rawUrl.contains("?")) "&" else "?"
                        "$rawUrl${sep}encoding=UTF-8"
                    }

                    val lang = sub.optString("language", "en").trim().lowercase()
                    val display = sub.optString("display", lang.uppercase()).trim()
                    val release = sub.optString("release").takeIf { it.isNotBlank() }
                    val isHearingImpaired = sub.optBoolean("isHearingImpaired", false)
                    val matchScore = sub.optInt("matchScore").takeIf { it > 0 }

                    val label = buildString {
                        append(display)
                        if (isHearingImpaired) append(" [CC]")
                        if (!release.isNullOrBlank()) append(" · $release")
                    }

                    result.add(
                        SubtitleTrack(
                            url = cleanUrl,
                            language = lang,
                            label = label,
                            format = SubFormat.SRT,
                            display = display,
                            release = release,
                            isHearingImpaired = isHearingImpaired,
                            matchScore = matchScore,
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching CineSrc subtitles for tmdbId=$tmdbId: ${e.message}")
        }

        result
    }
}

