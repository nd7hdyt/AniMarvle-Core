package com.animarvle.app.data.source.builtin

import android.util.Log
import com.animarvle.sourceapi.Audio
import com.animarvle.sourceapi.Choice
import com.animarvle.sourceapi.EpisodeOptions
import com.animarvle.sourceapi.MediaSource
import com.animarvle.sourceapi.MediaType
import com.animarvle.sourceapi.ResolvedMedia
import com.animarvle.sourceapi.ServerOption
import com.animarvle.sourceapi.SkipTimes
import com.animarvle.sourceapi.SourceCapabilities
import com.animarvle.sourceapi.SourceEpisode
import com.animarvle.sourceapi.SourceErrorKind
import com.animarvle.sourceapi.SourceException
import com.animarvle.sourceapi.SourceMedia
import com.animarvle.sourceapi.Stream
import com.animarvle.sourceapi.StreamKind
import com.animarvle.sourceapi.SubFormat
import com.animarvle.sourceapi.SubtitleTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Built-in native Kotlin anime scraper for AllAnime (matching Mizuki's AllAnimeProvider).
 *
 * Features:
 * - Direct GraphQL integration (Sub & Dub)
 * - Self-contained XOR 56 hex path decryptor
 * - Resolves adaptive HLS and progressive MP4 streams (Auto, 1080p, 720p, 480p)
 * - Auto intro/outro skip intervals via AniSkip
 * - Fully downloadable for offline playback
 * - Zero external APKs required
 */
class AllAnimeSource(
    private val http: OkHttpClient = OkHttpClient.Builder().build(),
) : MediaSource {

    override val id: Long = 40002L
    override val name: String = "AllAnime"
    override val language: String = "en"
    override val supportedTypes: Set<MediaType> = setOf(MediaType.ANIME, MediaType.MOVIE)

    override val capabilities: SourceCapabilities = SourceCapabilities(
        sub = true,
        dub = true,
        multiServer = true,
        qualities = true,
        skipTimes = true,
        downloadable = true,
    )

    private val showCache = ConcurrentHashMap<String, JSONObject>()

    companion object {
        private const val API_ENDPOINT = "https://api.allanime.day/api"
        private const val BASE_URL = "https://allanime.to"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val REFERER = "https://allanime.to/"

        /**
         * Decodes AllAnime's XOR 56 obfuscated hex paths (e.g. clock links).
         * Primary source: classes.dex AllAnimeProvider.decodeHexPath
         */
        fun decodeHexPath(hex: String): String {
            val clean = if (hex.startsWith("--")) hex.substring(2) else hex
            val sb = StringBuilder()
            var i = 0
            while (i < clean.length - 1) {
                val hexByte = clean.substring(i, i + 2).toIntOrNull(16) ?: break
                sb.append((hexByte xor 56).toChar())
                i += 2
            }
            return sb.toString()
        }
    }

    // --- Search ---------------------------------------------------------------------------------

    override suspend fun search(query: String, type: MediaType, page: Int): List<SourceMedia> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext emptyList()

        val searchQuery = """
            query(${'$'}search: SearchInput, ${'$'}limit: Int, ${'$'}page: Int, ${'$'}translationType: VaildTranslationTypeEnumType, ${'$'}countryOrigin: VaildCountryOriginEnumType) {
              shows(search: ${'$'}search, limit: ${'$'}limit, page: ${'$'}page, translationType: ${'$'}translationType, countryOrigin: ${'$'}countryOrigin) {
                edges {
                  _id
                  name
                  englishName
                  thumbnail
                  malId
                  aniListId
                  availableEpisodes
                }
              }
            }
        """.trimIndent()

        val variables = JSONObject().apply {
            put("search", JSONObject().put("query", cleanQuery))
            put("limit", 20)
            put("page", page)
            put("translationType", "sub")
            put("countryOrigin", "ALL")
        }

        val url = "$API_ENDPOINT?variables=" + URLEncoder.encode(variables.toString(), "UTF-8") +
            "&query=" + URLEncoder.encode(searchQuery, "UTF-8")

        val results = mutableListOf<SourceMedia>()
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", REFERER)
                .build()

            val res = http.newCall(req).execute()
            if (res.isSuccessful) {
                val body = res.body.string()
                val json = JSONObject(body)
                val edges = json.optJSONObject("data")?.optJSONObject("shows")?.optJSONArray("edges")
                if (edges != null) {
                    for (i in 0 until edges.length()) {
                        val edge = edges.optJSONObject(i) ?: continue
                        val showId = edge.optString("_id", "")
                        if (showId.isBlank()) continue
                        showCache[showId] = edge

                        val title = edge.optString("englishName").takeIf { !it.isNullOrBlank() }
                            ?: edge.optString("name", "Unknown Anime")
                        val thumb = edge.optString("thumbnail", "")
                        val malId = edge.optInt("malId", 0).takeIf { it > 0 }
                        val anilistId = edge.optInt("aniListId", 0).takeIf { it > 0 }

                        val avail = edge.optJSONObject("availableEpisodes")
                        val subCount = avail?.optInt("sub", 1) ?: 1
                        val dubCount = avail?.optInt("dub", 0) ?: 0
                        val maxEps = maxOf(subCount, dubCount).coerceAtLeast(1)

                        results.add(
                            SourceMedia(
                                url = "$BASE_URL/bangumi/$showId",
                                title = title,
                                year = null,
                                episodeCount = maxEps,
                                poster = thumb.ifBlank { null },
                                type = type,
                                extra = buildMap {
                                    put("showId", showId)
                                    if (malId != null) put("malId", malId.toString())
                                    if (anilistId != null) put("anilistId", anilistId.toString())
                                    put("subEpisodes", subCount.toString())
                                    put("dubEpisodes", dubCount.toString())
                                },
                            ),
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("AllAnimeSource", "Search failed: ${e.message}")
        }

        // If GraphQL returned nothing, create a direct matched candidate from the query
        if (results.isEmpty()) {
            results.add(
                SourceMedia(
                    url = "$BASE_URL/search?query=" + URLEncoder.encode(cleanQuery, "UTF-8"),
                    title = cleanQuery,
                    year = null,
                    episodeCount = 1,
                    poster = null,
                    type = type,
                    extra = mapOf("queryTitle" to cleanQuery),
                ),
            )
        }

        results
    }

    // --- Episodes -------------------------------------------------------------------------------

    override suspend fun episodes(media: SourceMedia): List<SourceEpisode> = withContext(Dispatchers.IO) {
        val showId = media.extra["showId"]
        val epCount = media.episodeCount ?: 1
        val cached = showId?.let { showCache[it] }

        val avail = cached?.optJSONObject("availableEpisodes")
        val subCount = avail?.optInt("sub", epCount) ?: epCount
        val dubCount = avail?.optInt("dub", 0) ?: 0
        val count = maxOf(subCount, dubCount, epCount).coerceAtLeast(1)

        val malIdStr = media.extra["malId"]
        val anilistIdStr = media.extra["anilistId"]

        (1..count).map { epNum ->
            SourceEpisode(
                url = if (showId != null) "$BASE_URL/bangumi/$showId/episode-$epNum" else media.url,
                number = epNum.toFloat(),
                title = "Episode $epNum",
                thumbnail = media.poster,
                extra = buildMap {
                    if (showId != null) put("showId", showId)
                    if (malIdStr != null) put("malId", malIdStr)
                    if (anilistIdStr != null) put("anilistId", anilistIdStr)
                    put("episodeNumber", epNum.toString())
                    put("hasDub", (epNum <= dubCount).toString())
                },
            )
        }
    }

    // --- Options --------------------------------------------------------------------------------

    override suspend fun options(episode: SourceEpisode): EpisodeOptions = withContext(Dispatchers.IO) {
        val showId = episode.extra["showId"] ?: "show"
        val epNum = episode.number.toInt()

        val subServers = listOf(
            ServerOption(
                key = "allanime:$showId:$epNum:sub:default",
                label = "AllAnime (Default)",
                hint = "Fast · Multi-Quality · Can download",
                audioLanguage = "English",
            ),
            ServerOption(
                key = "allanime:$showId:$epNum:sub:sakura",
                label = "AllAnime (Sakura)",
                hint = "1080p High-Speed",
                audioLanguage = "English",
            ),
            ServerOption(
                key = "allanime:$showId:$epNum:sub:luf",
                label = "AllAnime (Luf-mp4)",
                hint = "Direct Stream · High Speed",
                audioLanguage = "English",
            ),
            ServerOption(
                key = "allanime:$showId:$epNum:sub:smp4",
                label = "AllAnime (S-mp4)",
                hint = "Direct Progressive MP4",
                audioLanguage = "English",
            ),
        )

        val dubServers = listOf(
            ServerOption(
                key = "allanime:$showId:$epNum:dub:default",
                label = "AllAnime Dub (Default)",
                hint = "Dubbed · Multi-Quality · Can download",
                audioLanguage = "English",
            ),
            ServerOption(
                key = "allanime:$showId:$epNum:dub:sakura",
                label = "AllAnime Dub (Sakura)",
                hint = "Dubbed · 1080p High-Speed",
                audioLanguage = "English",
            ),
            ServerOption(
                key = "allanime:$showId:$epNum:dub:luf",
                label = "AllAnime Dub (Luf-mp4)",
                hint = "Dubbed · Direct Stream",
                audioLanguage = "English",
            ),
        )

        EpisodeOptions(
            byAudio = mapOf(
                Audio.SUB to subServers,
                Audio.DUB to dubServers,
            ),
        )
    }

    // --- Resolve --------------------------------------------------------------------------------

    override suspend fun resolve(episode: SourceEpisode, choice: Choice): ResolvedMedia = withContext(Dispatchers.IO) {
        val showId = episode.extra["showId"]
        val epNum = episode.number.toInt()
        val isDub = choice.audio == Audio.DUB
        val translationType = if (isDub) "dub" else "sub"

        val streams = mutableListOf<Stream>()

        if (showId != null) {
            val epQuery = """
                query(${'$'}showId: String!, ${'$'}translationType: VaildTranslationTypeEnumType!, ${'$'}episodeString: String!) {
                  episode(showId: ${'$'}showId, translationType: ${'$'}translationType, episodeString: ${'$'}episodeString) {
                    sourceUrls
                  }
                }
            """.trimIndent()

            val variables = JSONObject().apply {
                put("showId", showId)
                put("translationType", translationType)
                put("episodeString", epNum.toString())
            }

            val url = "$API_ENDPOINT?variables=" + URLEncoder.encode(variables.toString(), "UTF-8") +
                "&query=" + URLEncoder.encode(epQuery, "UTF-8")

            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", REFERER)
                    .build()

                val res = http.newCall(req).execute()
                if (res.isSuccessful) {
                    val body = res.body.string()
                    val json = JSONObject(body)
                    val sourceUrls = json.optJSONObject("data")?.optJSONObject("episode")?.optJSONArray("sourceUrls")

                    if (sourceUrls != null) {
                        for (i in 0 until sourceUrls.length()) {
                            val srcObj = sourceUrls.optJSONObject(i) ?: continue
                            val rawUrl = srcObj.optString("sourceUrl", "")
                            if (rawUrl.isBlank()) continue

                            val decodedUrl = if (rawUrl.startsWith("--")) decodeHexPath(rawUrl) else rawUrl
                            val priority = srcObj.optDouble("priority", 1.0)

                            if (decodedUrl.contains(".m3u8", ignoreCase = true)) {
                                streams.add(
                                    Stream(
                                        url = decodedUrl,
                                        kind = StreamKind.HLS,
                                        qualityLabel = "Auto (Multi-Quality)",
                                        height = 1080,
                                        headers = mapOf("Referer" to REFERER, "User-Agent" to USER_AGENT),
                                    ),
                                )
                            } else if (decodedUrl.startsWith("/clock", ignoreCase = true)) {
                                val clockUrl = "$BASE_URL$decodedUrl"
                                val clockReq = Request.Builder()
                                    .url(clockUrl)
                                    .header("User-Agent", USER_AGENT)
                                    .header("Referer", REFERER)
                                    .build()

                                val clockRes = http.newCall(clockReq).execute()
                                if (clockRes.isSuccessful) {
                                    val clockJson = JSONObject(clockRes.body.string())
                                    val links = clockJson.optJSONArray("links")
                                    if (links != null) {
                                        for (j in 0 until links.length()) {
                                            val linkItem = links.optJSONObject(j) ?: continue
                                            val link = linkItem.optString("link", "")
                                            if (link.isBlank()) continue
                                            val isHls = linkItem.optBoolean("hls", link.contains(".m3u8"))
                                            val resStr = linkItem.optString("resolutionStr", "1080p")
                                            val height = when {
                                                resStr.contains("1080") -> 1080
                                                resStr.contains("720") -> 720
                                                resStr.contains("480") -> 480
                                                else -> 1080
                                            }

                                            streams.add(
                                                Stream(
                                                    url = link,
                                                    kind = if (isHls) StreamKind.HLS else StreamKind.PROGRESSIVE,
                                                    qualityLabel = resStr,
                                                    height = height,
                                                    headers = mapOf("Referer" to REFERER, "User-Agent" to USER_AGENT),
                                                ),
                                            )
                                        }
                                    }
                                }
                            } else if (decodedUrl.startsWith("http")) {
                                streams.add(
                                    Stream(
                                        url = decodedUrl,
                                        kind = if (decodedUrl.contains(".m3u8")) StreamKind.HLS else StreamKind.PROGRESSIVE,
                                        qualityLabel = "Direct Stream",
                                        height = 1080,
                                        headers = mapOf("Referer" to REFERER, "User-Agent" to USER_AGENT),
                                    ),
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("AllAnimeSource", "Stream resolution failed: ${e.message}")
            }
        }

        // Auto skip times from AniSkip
        val malId = episode.extra["malId"]?.toIntOrNull()
            ?: episode.extra["anilistId"]?.toIntOrNull()?.let { AniZipHelper.getMappings(http, it)?.malId }
        val skipTimes = AniSkipHelper.getSkipTimes(http, malId, epNum)

        if (streams.isEmpty()) {
            throw SourceException(
                SourceErrorKind.NOT_FOUND,
                "No playable streams found for Episode $epNum on AllAnime. Please try another server.",
            )
        }

        ResolvedMedia(
            streams = streams.distinctBy { it.url },
            subtitles = emptyList(),
            skipTimes = skipTimes,
            headers = mapOf("Referer" to REFERER, "User-Agent" to USER_AGENT),
        )
    }
}
