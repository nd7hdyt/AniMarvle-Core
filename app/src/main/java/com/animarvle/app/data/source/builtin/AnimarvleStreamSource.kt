package com.animarvle.app.data.source.builtin

import android.content.Context
import android.util.Log
import com.animarvle.app.ui.profile.PlayerSettingsFragment
import com.animarvle.sourceapi.Audio
import com.animarvle.sourceapi.Choice
import com.animarvle.sourceapi.EpisodeOptions
import com.animarvle.sourceapi.MediaSource
import com.animarvle.sourceapi.MediaType
import com.animarvle.sourceapi.ResolvedMedia
import com.animarvle.sourceapi.ServerOption
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
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Built-in Cloudflare Worker Edge Relay Source (AniVortex-style HLS Architecture).
 *
 * Routes requests to a high-speed Cloudflare Worker that resolves, rewrites, and edge-caches
 * adaptive HLS (.m3u8) streams for Movies, Series, and Anime.
 */
class AnimarvleStreamSource(
    private val http: OkHttpClient = OkHttpClient.Builder().build(),
    private val appContext: Context? = null,
) : MediaSource {

    override val id: Long = 40004L
    override val name: String = "Animarvle Turbo (HLS)"
    override val language: String = "all"
    override val supportedTypes: Set<MediaType> = setOf(MediaType.MOVIE, MediaType.ANIME)

    override val capabilities: SourceCapabilities = SourceCapabilities(
        sub = true,
        dub = false, // worker provides Sub for anime; Dub must route to anime extensions (Zoro/Gogoanime)
        multiServer = true,
        qualities = true,
        skipTimes = true,
        downloadable = true,
    )

    private fun getWorkerBaseUrl(): String {
        val prefs = appContext?.getSharedPreferences(PlayerSettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val custom = prefs?.getString(KEY_WORKER_URL, null)
        return custom?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: DEFAULT_WORKER_URL
    }

    // --- Search ---------------------------------------------------------------------------------

    override suspend fun search(query: String, type: MediaType, page: Int): List<SourceMedia> = withContext(Dispatchers.IO) {
        val cleanQuery = query
            .replace(Regex("\\s*\\((?:dub|sub|raw)\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[(?:dub|sub|raw)\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+(?:dub|sub)\\s*$", RegexOption.IGNORE_CASE), "")
            .trim()
        if (cleanQuery.isBlank()) return@withContext emptyList()

        val baseUrl = getWorkerBaseUrl()
        val searchUrl = "$baseUrl/api/search?q=" + URLEncoder.encode(cleanQuery, "UTF-8")

        val results = mutableListOf<SourceMedia>()
        runCatching {
            val req = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", USER_AGENT)
                .build()

            http.newCall(req).execute().use { res ->
                if (res.isSuccessful) {
                    val body = res.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val items = json.optJSONArray("results")
                    if (items != null) {
                        for (i in 0 until items.length()) {
                            val item = items.optJSONObject(i) ?: continue
                            val id = item.optString("id", "")
                            val title = item.optString("title", cleanQuery)
                            val itemType = if (item.optString("type") == "tv") MediaType.ANIME else MediaType.MOVIE
                            val year = item.optString("year").toIntOrNull()
                            val poster = item.optString("poster").takeIf { !it.isNullOrBlank() }

                            results.add(
                                SourceMedia(
                                    url = "$baseUrl/resolve?tmdb=$id&type=${item.optString("type", "movie")}",
                                    title = title,
                                    year = year,
                                    episodeCount = if (itemType == MediaType.MOVIE) 1 else 12,
                                    poster = poster,
                                    type = type,
                                    extra = mapOf(
                                        "tmdbId" to id,
                                        "title" to title,
                                        "year" to (year?.toString() ?: ""),
                                        "mediaType" to (if (type == MediaType.ANIME) "anime" else item.optString("type", "movie")),
                                    ),
                                ),
                            )
                        }
                    }
                }
            }
        }.onFailure { e ->
            Log.w(TAG, "Search via worker failed: ${e.message}")
        }

        // Direct candidate if worker search returned nothing
        if (results.isEmpty()) {
            results.add(
                SourceMedia(
                    url = "$baseUrl/resolve?title=" + URLEncoder.encode(cleanQuery, "UTF-8"),
                    title = cleanQuery,
                    year = null,
                    episodeCount = if (type == MediaType.MOVIE) 1 else 12,
                    poster = null,
                    type = type,
                    extra = mapOf(
                        "title" to cleanQuery,
                        "mediaType" to (if (type == MediaType.ANIME) "anime" else if (type == MediaType.MOVIE) "movie" else "tv"),
                    ),
                ),
            )
        }

        results
    }

    // --- Episodes -------------------------------------------------------------------------------

    override suspend fun episodes(media: SourceMedia): List<SourceEpisode> = withContext(Dispatchers.IO) {
        val tmdbId = media.extra["tmdbId"].orEmpty()
        val title = media.extra["title"] ?: media.title
        val year = media.extra["year"].orEmpty()
        val mediaType = media.extra["mediaType"] ?: if (media.type == MediaType.MOVIE) "movie" else if (media.type == MediaType.ANIME) "anime" else "tv"
        val season = media.extra["season"] ?: "1"
        val count = media.episodeCount ?: 1

        (1..count).map { epNum ->
            SourceEpisode(
                url = "${media.url}&season=$season&episode=$epNum",
                number = epNum.toFloat(),
                title = if (count == 1) title else "Episode $epNum",
                thumbnail = media.poster,
                extra = buildMap {
                    put("tmdbId", tmdbId)
                    put("title", title)
                    put("year", year)
                    put("mediaType", mediaType)
                    put("season", season)
                    put("episode", epNum.toString())
                },
            )
        }
    }

    // --- Options --------------------------------------------------------------------------------

    override suspend fun options(episode: SourceEpisode): EpisodeOptions = withContext(Dispatchers.IO) {
        val baseUrl = getWorkerBaseUrl()
        val title = episode.extra["title"] ?: episode.title
        val tmdbId = episode.extra["tmdbId"].orEmpty()
        val year = episode.extra["year"].orEmpty()
        val mediaType = episode.extra["mediaType"] ?: "anime"
        val season = episode.extra["season"] ?: "1"
        val epNum = episode.extra["episode"] ?: "1"

        val resolveUrl = buildString {
            append("$baseUrl/api/stream?")
            if (tmdbId.isNotBlank()) append("tmdb=").append(URLEncoder.encode(tmdbId, "UTF-8")).append("&")
            append("title=").append(URLEncoder.encode(title, "UTF-8"))
            if (year.isNotBlank()) append("&year=").append(URLEncoder.encode(year, "UTF-8"))
            append("&type=").append(mediaType)
            append("&season=").append(season)
            append("&episode=").append(epNum)
            append("&v=2")
        }

        val serverList = mutableListOf<ServerOption>()

        runCatching {
            val req = Request.Builder()
                .url(resolveUrl)
                .header("User-Agent", USER_AGENT)
                .build()

            http.newCall(req).execute().use { res ->
                if (res.isSuccessful) {
                    val body = res.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val streams = json.optJSONArray("streams")
                    if (streams != null) {
                        for (i in 0 until streams.length()) {
                            val s = streams.optJSONObject(i) ?: continue
                            val isAnime = mediaType.equals("anime", ignoreCase = true)
                            val rawAudio = s.optString("audioLanguage", s.optString("audio", "")).trim()
                            val audioLang = if (isAnime) {
                                if (rawAudio.contains("dub", ignoreCase = true)) "English" else "Japanese"
                            } else {
                                rawAudio.ifBlank { "English" }
                            }
                            val rawServer = s.optString("server", "").trim()
                            val serverName = when {
                                rawServer.isNotBlank() && !rawServer.startsWith("Edge ") -> rawServer
                                rawServer.contains("spiral", ignoreCase = true) || i == 0 -> "Spiral"
                                rawServer.contains("turbo", ignoreCase = true) || i == 1 -> "Turbo"
                                rawServer.contains("fast", ignoreCase = true) || i == 2 -> "FastX"
                                else -> "Edge ${i + 1}"
                            }
                            val streamUrl = s.optString("url", "")
                            val quality = s.optString("quality", "1080p")
                            val kindStr = s.optString("kind", if (streamUrl.contains(".m3u8")) "hls" else "mp4")

                            // Parse and cache subtitles if returned
                            val subsArray = s.optJSONArray("subtitles")
                            if (subsArray != null && subsArray.length() > 0) {
                                val tracks = mutableListOf<SubtitleTrack>()
                                for (j in 0 until subsArray.length()) {
                                    val sub = subsArray.optJSONObject(j) ?: continue
                                    val subUrl = sub.optString("url")
                                    val label = sub.optString("label", "English")
                                    val lang = sub.optString("language", "en")
                                    val formatStr = sub.optString("type", "srt").uppercase()
                                    val format = when {
                                        formatStr.contains("SRT") -> SubFormat.SRT
                                        formatStr.contains("ASS") -> SubFormat.ASS
                                        else -> SubFormat.VTT
                                    }
                                    if (subUrl.isNotBlank()) {
                                        tracks.add(SubtitleTrack(url = subUrl, language = lang, label = label, format = format))
                                    }
                                }
                                if (tracks.isNotEmpty()) {
                                    subtitlesCache[streamUrl] = tracks
                                }
                            }

                            // Parse and cache multiple qualities (1080p, 720p, 480p, 360p)
                            val qObj = s.optJSONObject("qualities")
                            if (qObj != null) {
                                val qMap = mutableMapOf<String, String>()
                                val keys = qObj.keys()
                                while (keys.hasNext()) {
                                    val k = keys.next()
                                    val u = qObj.optString(k)
                                    if (u.isNotBlank()) qMap[k] = u
                                }
                                if (qMap.isNotEmpty()) {
                                    qualitiesCache[streamUrl] = qMap
                                }
                            }

                            if (streamUrl.isNotBlank()) {
                                serverList.add(
                                    ServerOption(
                                        key = "turbo:$i|$quality|$streamUrl|$kindStr",
                                        label = serverName,
                                        hint = "$audioLang · $quality",
                                        audioLanguage = audioLang,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }.onFailure { e ->
            Log.w(TAG, "Fetching options from worker failed: ${e.message}")
        }

        if (serverList.isEmpty()) {
            throw SourceException(
                kind = SourceErrorKind.NOT_FOUND,
                message = "No streams available from Animarvle Edge Worker",
            )
        }

        val byAudio = mutableMapOf<Audio, List<ServerOption>>()
        val isAnime = mediaType.equals("anime", ignoreCase = true)

        if (isAnime) {
            val explicitDub = serverList.filter {
                it.audioLanguage?.contains("dub", ignoreCase = true) == true ||
                it.hint?.contains("dub", ignoreCase = true) == true ||
                it.label.contains("dub", ignoreCase = true)
            }
            val explicitSub = serverList.filter {
                it.audioLanguage?.contains("sub", ignoreCase = true) == true ||
                it.audioLanguage?.contains("japanese", ignoreCase = true) == true ||
                it.audioLanguage?.contains("chinese", ignoreCase = true) == true ||
                it.hint?.contains("sub", ignoreCase = true) == true ||
                it.label.contains("sub", ignoreCase = true)
            }

            byAudio[Audio.SUB] = explicitSub.ifEmpty { serverList }
            byAudio[Audio.RAW] = explicitSub.ifEmpty { serverList }
            byAudio[Audio.DUB] = explicitDub.ifEmpty { serverList }
        } else {
            byAudio[Audio.RAW] = serverList
            byAudio[Audio.SUB] = serverList
            byAudio[Audio.DUB] = serverList
        }

        EpisodeOptions(byAudio = byAudio)
    }

    // --- Resolve --------------------------------------------------------------------------------

    override suspend fun resolve(episode: SourceEpisode, choice: Choice): ResolvedMedia = withContext(Dispatchers.IO) {
        val parts = choice.serverKey.split("|")
        val streamUrl = parts.getOrNull(2)
            ?: throw SourceException(SourceErrorKind.NOT_FOUND, "Invalid stream key: ${choice.serverKey}")
        val quality = parts.getOrNull(1) ?: "1080p"
        val kindStr = parts.getOrNull(3)

        val isHls = kindStr == "hls" || streamUrl.contains(".m3u8") || streamUrl.contains("/proxy/m3u8")
        val streamKind = if (isHls) StreamKind.HLS else StreamKind.PROGRESSIVE

        val cachedSubs = subtitlesCache[streamUrl] ?: emptyList()
        val qMap = qualitiesCache[streamUrl]

        fun makeHeaders(url: String): Map<String, String> = when {
            url.contains("googleusercontent.com") || url.contains("video-downloads") -> mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "*/*",
            )
            url.contains("pelisplushd") || url.contains("embed69") -> mapOf(
                "Referer" to "https://pelisplushd.bz/",
                "User-Agent" to USER_AGENT,
                "Accept" to "*/*",
            )
            url.contains("/proxy/") -> mapOf(
                "Accept" to "*/*",
            )
            else -> mapOf(
                "Origin" to "https://vidlink.pro",
                "Referer" to "https://vidlink.pro/",
                "Accept" to "*/*",
            )
        }

        val streams = if (!qMap.isNullOrEmpty()) {
            val list = mutableListOf<Stream>()
            val sortedKeys = qMap.keys.sortedByDescending { it.replace("p", "").toIntOrNull() ?: 0 }
            for (qKey in sortedKeys) {
                val qUrl = qMap[qKey] ?: continue
                val qIsHls = qUrl.contains(".m3u8") || qUrl.contains("/proxy/m3u8")
                val qKind = if (qIsHls) StreamKind.HLS else StreamKind.PROGRESSIVE
                val h = qKey.replace("p", "").toIntOrNull()
                list.add(
                    Stream(
                        url = qUrl,
                        kind = qKind,
                        qualityLabel = qKey,
                        height = h,
                        headers = makeHeaders(qUrl),
                    ),
                )
            }
            list
        } else {
            val h = quality.replace("p", "").toIntOrNull()
            listOf(
                Stream(
                    url = streamUrl,
                    kind = streamKind,
                    qualityLabel = quality,
                    height = h,
                    headers = makeHeaders(streamUrl),
                ),
            )
        }

        Log.i(TAG, "Playing Cloudflare stream with ${streams.size} qualities: $streamUrl ($quality, kind=$streamKind, subs=${cachedSubs.size})")

        ResolvedMedia(
            streams = streams,
            subtitles = cachedSubs,
        )
    }

    companion object {
        private const val TAG = "AnimarvleStreamSource"
        const val KEY_WORKER_URL = "cloudflare_worker_url"
        const val DEFAULT_WORKER_URL = "https://animarvle-edge.grandfootball21.workers.dev"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        val subtitlesCache = java.util.concurrent.ConcurrentHashMap<String, List<SubtitleTrack>>()
        val qualitiesCache = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()
    }
}
