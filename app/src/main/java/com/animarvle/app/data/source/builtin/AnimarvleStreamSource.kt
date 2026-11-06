package com.animarvle.app.data.source.builtin

import android.content.Context
import android.util.Log
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
import java.util.Collections

/**
 * Built-in Cloudflare Worker Edge Relay Source (AniVortex-style HLS Architecture).
 *
 * Routes requests to a high-speed Cloudflare Worker that resolves, rewrites, and edge-caches
 * adaptive HLS (.m3u8) and direct streams for Movies, Series, and Anime.
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
        dub = true,
        multiServer = true,
        qualities = true,
        skipTimes = true,
        downloadable = true,
    )

    private fun getWorkerBaseUrl(): String = DEFAULT_WORKER_URL

    // --- Search ---------------------------------------------------------------------------------

    override suspend fun search(query: String, type: MediaType, page: Int): List<SourceMedia> = withContext(Dispatchers.IO) {
        val cleanQuery = query
            .replace(Regex("\\s*\\((?:dub|sub|raw)\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[(?:dub|sub|raw)\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+(?:dub|sub)\\s*$", RegexOption.IGNORE_CASE), "")
            .trim()
        if (cleanQuery.isBlank()) return@withContext emptyList()

        val isAdultAnime = com.animarvle.app.data.MetadataRepository.isAdultTitle(cleanQuery)
        val baseUrl = getWorkerBaseUrl()

        if (isAdultAnime) {
            val encodedTitle = URLEncoder.encode(cleanQuery, "UTF-8")
            val romajiMatch = if (cleanQuery.contains("vampire vixen", ignoreCase = true)) "Itadaki Seieki" else ""
            val romajiParam = if (romajiMatch.isNotBlank()) "&romaji=" + URLEncoder.encode(romajiMatch, "UTF-8") else ""
            return@withContext listOf(
                SourceMedia(
                    url = "$baseUrl/resolve?title=$encodedTitle$romajiParam&type=anime&adult=1",
                    title = cleanQuery,
                    year = null,
                    episodeCount = null,
                    poster = null,
                    type = MediaType.ANIME,
                    extra = mapOf(
                        "title" to cleanQuery,
                        "romaji" to romajiMatch,
                        "mediaType" to "anime",
                        "isAdult" to "1",
                    ),
                )
            )
        }

        val searchUrl = "$baseUrl/api/search?q=" + URLEncoder.encode(cleanQuery, "UTF-8")

        val results = mutableListOf<SourceMedia>()
        runCatching {
            val req = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", USER_AGENT)
                .build()

            http.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                Log.i(TAG, "Worker search for '$cleanQuery' code=${res.code} bodyLength=${body.length}")
                if (res.isSuccessful) {
                    val json = JSONObject(body)
                    val items = json.optJSONArray("results")
                    if (items != null) {
                        for (i in 0 until items.length()) {
                            val item = items.optJSONObject(i) ?: continue
                            val id = item.optString("id", "")
                            val title = item.optString("title", cleanQuery)
                            val itemBackendType = item.optString("type", if (type == MediaType.MOVIE) "movie" else "anime")
                            val year = item.optString("year").toIntOrNull()
                            val poster = item.optString("poster").takeIf { !it.isNullOrBlank() }
                            val epCount = item.optInt("episodeCount", 0).takeIf { it > 0 }
                                ?: if (itemBackendType == "movie") 1 else null

                            results.add(
                                SourceMedia(
                                    url = "$baseUrl/resolve?tmdb=$id&type=$itemBackendType",
                                    title = title,
                                    year = year,
                                    episodeCount = epCount,
                                    poster = poster,
                                    type = type,
                                    extra = mapOf(
                                        "tmdbId" to id,
                                        "title" to title,
                                        "year" to (year?.toString() ?: ""),
                                        "mediaType" to itemBackendType,
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
                    episodeCount = if (type == MediaType.MOVIE) 1 else null,
                    poster = null,
                    type = type,
                    extra = mapOf(
                        "title" to cleanQuery,
                        "mediaType" to (if (type == MediaType.ANIME) "anime" else "movie"),
                    ),
                ),
            )
        }

        results
    }

    // --- Episodes -------------------------------------------------------------------------------

    override suspend fun episodes(media: SourceMedia): List<SourceEpisode> = withContext(Dispatchers.IO) {
        var tmdbId = media.extra["tmdbId"].orEmpty()
        if (tmdbId.isBlank()) {
            tmdbId = Regex("[?&]tmdb=([0-9]+)").find(media.url)?.groupValues?.get(1)
                ?: Regex("[?&]id=([0-9]+)").find(media.url)?.groupValues?.get(1).orEmpty()
        }
        val title = media.extra["title"] ?: media.title
        val romaji = media.extra["romaji"].orEmpty()
        val synonyms = media.extra["synonyms"].orEmpty()
        val year = media.extra["year"]?.takeIf { it.isNotBlank() } ?: media.year?.toString().orEmpty()
        var mediaType = media.extra["mediaType"].orEmpty()
        if (mediaType.isBlank()) {
            mediaType = Regex("[?&]type=([a-zA-Z0-9]+)").find(media.url)?.groupValues?.get(1).orEmpty()
        }
        if (mediaType.isBlank()) {
            mediaType = if (media.type == MediaType.MOVIE) "movie" else if (media.type == MediaType.ANIME) "anime" else "tv"
        }
        val season = media.extra["season"]
            ?: Regex("[?&]season=([0-9]+)").find(media.url)?.groupValues?.get(1)
            ?: "1"
        val count = media.episodeCount?.takeIf { it > 0 } ?: 1

        val isAdultOrAnime = mediaType.equals("anime", ignoreCase = true) ||
            media.extra["isAdult"] == "1" ||
            com.animarvle.app.ui.player.HistoryRepository.isAnimeTitle(title)

        val adultMetadataMap = if (isAdultOrAnime) {
            runCatching {
                val baseUrl = getWorkerBaseUrl()
                val qTitle = URLEncoder.encode(title, "UTF-8")
                val qRomaji = if (romaji.isNotBlank()) "&romaji=" + URLEncoder.encode(romaji, "UTF-8") else ""
                val qSynonyms = if (synonyms.isNotBlank()) "&synonyms=" + URLEncoder.encode(synonyms, "UTF-8") else ""
                val epReq = Request.Builder()
                    .url("$baseUrl/api/episodes?title=$qTitle$qRomaji$qSynonyms")
                    .header("User-Agent", "AnimarvleApp/1.0")
                    .build()
                http.newCall(epReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        val json = JSONObject(body)
                        if (json.optBoolean("success", false)) {
                            val arr = json.optJSONArray("episodes") ?: org.json.JSONArray()
                            val m = mutableMapOf<Int, Pair<String, String?>>()
                            for (i in 0 until arr.length()) {
                                val item = arr.getJSONObject(i)
                                val num = item.optInt("episode", i + 1)
                                val epTitle = item.optString("title", "Episode $num")
                                val thumb = if (item.has("thumbnail") && !item.isNull("thumbnail")) item.getString("thumbnail") else null
                                m[num] = epTitle to thumb
                            }
                            m
                        } else null
                    } else null
                }
            }.getOrNull()
        } else null

        val finalCount = if (!adultMetadataMap.isNullOrEmpty()) {
            maxOf(count, adultMetadataMap.keys.maxOrNull() ?: count)
        } else {
            count
        }

        (1..finalCount).map { epNum ->
            val meta = adultMetadataMap?.get(epNum)
            val epTitle = meta?.first?.takeIf { it.isNotBlank() } ?: (if (finalCount == 1) title else "Episode $epNum")
            val epThumb = meta?.second?.takeIf { it.isNotBlank() } ?: media.poster
            SourceEpisode(
                url = "${media.url}&season=$season&episode=$epNum",
                number = epNum.toFloat(),
                title = epTitle,
                thumbnail = epThumb,
                extra = buildMap {
                    if (tmdbId.isNotBlank()) put("tmdbId", tmdbId)
                    put("title", title)
                    if (romaji.isNotBlank()) put("romaji", romaji)
                    if (synonyms.isNotBlank()) put("synonyms", synonyms)
                    if (year.isNotBlank()) put("year", year)
                    put("mediaType", mediaType)
                    put("season", season)
                    put("episode", epNum.toString())
                    media.extra["isAdult"]?.let { put("isAdult", it) }
                    media.extra["lang"]?.let { put("lang", it) }
                },
            )
        }
    }

    // --- Options --------------------------------------------------------------------------------

    override suspend fun options(episode: SourceEpisode): EpisodeOptions = withContext(Dispatchers.IO) {
        val baseUrl = getWorkerBaseUrl()
        val rawTitle = episode.extra["title"] ?: episode.title.orEmpty()
        val title = rawTitle
            .replace(Regex("\\s*\\((?:dub|sub|raw)\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[(?:dub|sub|raw)\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+(?:dub|sub)\\s*$", RegexOption.IGNORE_CASE), "")
            .trim()
        val romaji = episode.extra["romaji"].orEmpty()
        val synonyms = episode.extra["synonyms"].orEmpty()
        val reqLang = episode.extra["lang"].orEmpty()
        var tmdbId = episode.extra["tmdbId"].orEmpty()
        if (tmdbId.isBlank()) {
            tmdbId = Regex("[?&]tmdb=([0-9]+)").find(episode.url)?.groupValues?.get(1)
                ?: Regex("[?&]id=([0-9]+)").find(episode.url)?.groupValues?.get(1).orEmpty()
        }
        val year = episode.extra["year"].orEmpty()
        var mediaType = episode.extra["mediaType"].orEmpty()
        if (mediaType.isBlank()) {
            mediaType = Regex("[?&]type=([a-zA-Z0-9]+)").find(episode.url)?.groupValues?.get(1).orEmpty()
        }
        if (mediaType.isBlank()) {
            mediaType = if (tmdbId.isNotBlank()) "movie" else "anime"
        }
        val season = episode.extra["season"]?.takeIf { it.isNotBlank() }
            ?: Regex("[?&]season=([0-9]+)").find(episode.url)?.groupValues?.get(1)
            ?: "1"
        val epNum = episode.extra["episode"]?.takeIf { it.isNotBlank() }
            ?: (if (episode.number > 0f) episode.number.toInt().toString() else null)
            ?: Regex("[?&]episode=([0-9]+)").find(episode.url)?.groupValues?.get(1)
            ?: "1"

        val serverList = mutableListOf<ServerOption>()

        val isAdult = episode.extra["isAdult"] == "1" ||
            com.animarvle.app.data.MetadataRepository.isAdultTitle(title) ||
            com.animarvle.app.data.MetadataRepository.isAdultTitle(romaji)

        val effectiveRomaji = if (romaji.isBlank() && title.contains("vampire vixen", ignoreCase = true)) {
            "Itadaki Seieki"
        } else {
            romaji
        }

        val isMovie = mediaType.equals("movie", ignoreCase = true)
        val seasonNum = season.toIntOrNull()
        val episodeNum = epNum.toIntOrNull()
        val hasExplicitEpisode = (seasonNum != null && seasonNum > 0) || (episodeNum != null && episodeNum > 1)
        val isSeries = !isMovie && (hasExplicitEpisode || mediaType.equals("tv", ignoreCase = true))

        val typesToTry = when {
            isAdult -> listOf("anime")
            isSeries -> if (mediaType.equals("anime", ignoreCase = true)) listOf("anime", "tv") else listOf("tv", "anime")
            else -> buildList {
                add("movie")
                if (mediaType.equals("anime", ignoreCase = true)) add("anime")
            }
        }

        var resolvedType = if (isAdult) "anime" else mediaType

        for (tryType in typesToTry) {
            val queryAttempts = buildList {
                // 1. Specific query (with tmdbId, year, romaji, synonyms, and optional lang)
                add(
                    buildString {
                        append("$baseUrl/api/stream?")
                        if (isAdult) append("adult=1&")
                        if (tmdbId.isNotBlank() && !isAdult) append("tmdb=").append(URLEncoder.encode(tmdbId, "UTF-8")).append("&")
                        append("title=").append(URLEncoder.encode(title, "UTF-8"))
                        if (effectiveRomaji.isNotBlank()) append("&romaji=").append(URLEncoder.encode(effectiveRomaji, "UTF-8"))
                        if (synonyms.isNotBlank()) append("&synonyms=").append(URLEncoder.encode(synonyms, "UTF-8"))
                        if (year.isNotBlank() && !isAdult) append("&year=").append(URLEncoder.encode(year, "UTF-8"))
                        if (reqLang.isNotBlank()) append("&lang=").append(URLEncoder.encode(reqLang, "UTF-8"))
                        append("&type=").append(tryType)
                        append("&season=").append(season)
                        append("&episode=").append(epNum)
                        append("&v=2")
                    }
                )
                // 2. Broad fallback query if specific query had tmdbId or year (retains romaji and synonyms!)
                if (!isAdult && (tmdbId.isNotBlank() || year.isNotBlank())) {
                    add(
                        buildString {
                            append("$baseUrl/api/stream?")
                            append("title=").append(URLEncoder.encode(title, "UTF-8"))
                            if (effectiveRomaji.isNotBlank()) append("&romaji=").append(URLEncoder.encode(effectiveRomaji, "UTF-8"))
                            if (synonyms.isNotBlank()) append("&synonyms=").append(URLEncoder.encode(synonyms, "UTF-8"))
                            if (reqLang.isNotBlank()) append("&lang=").append(URLEncoder.encode(reqLang, "UTF-8"))
                            append("&type=").append(tryType)
                            append("&season=").append(season)
                            append("&episode=").append(epNum)
                            append("&v=2")
                        }
                    )
                }
            }

            var streamsFound = false
            for (resolveUrl in queryAttempts) {
                runCatching {
                    val req = Request.Builder()
                        .url(resolveUrl)
                        .header("User-Agent", USER_AGENT)
                        .build()

                    http.newCall(req).execute().use { res ->
                        val body = res.body?.string().orEmpty()
                        Log.i(TAG, "Worker stream response for '$title' (type=$tryType, url=$resolveUrl) code=${res.code} bodyLength=${body.length}")
                        if (res.isSuccessful) {
                            val json = JSONObject(body)
                            val streams = json.optJSONArray("streams")
                            if (streams != null && streams.length() > 0) {
                                resolvedType = tryType
                                streamsFound = true
                                for (i in 0 until streams.length()) {
                                    val s = streams.optJSONObject(i) ?: continue
                                    val isAnime = tryType.equals("anime", ignoreCase = true)
                                    val rawAudio = s.optString("audioLanguage", s.optString("audio", "")).trim()
                                    val audioCategory = s.optString("audio", "").uppercase()
                                    val audioLang = when {
                                        rawAudio.isNotBlank() && !rawAudio.equals("SUB", ignoreCase = true) && !rawAudio.equals("DUB", ignoreCase = true) -> rawAudio
                                        isAnime -> if (audioCategory == "DUB" || rawAudio.contains("dub", ignoreCase = true)) "English" else "Japanese"
                                        else -> "English"
                                    }

                                    val rawServer = s.optString("server", "").trim()
                                    val serverName = when {
                                        rawServer.contains("spiral", ignoreCase = true) -> "Spiral"
                                        rawServer.contains("turbo", ignoreCase = true) -> "Turbo"
                                        rawServer.contains("fast", ignoreCase = true) -> "FastX"
                                        rawServer.isNotBlank() && !rawServer.startsWith("Edge ") -> rawServer
                                        i == 0 -> "Spiral"
                                        i == 1 -> "Turbo"
                                        i == 2 -> "FastX"
                                        else -> "Edge ${i + 1}"
                                    }
                                    val streamUrl = s.optString("url", "")
                                    if (streamUrl.isBlank()) continue

                                    // Strictly reject rogue adult video domains on mainstream content
                                    if (!isAdult) {
                                        val checkUrl = streamUrl.lowercase()
                                        if (checkUrl.contains("povblowjob") || checkUrl.contains("animesex") ||
                                            checkUrl.contains("hentaimama") || checkUrl.contains("hentaini") ||
                                            checkUrl.contains("clean-tube-player")
                                        ) {
                                            Log.w(TAG, "Rejected rogue adult stream on mainstream content ($title): $streamUrl")
                                            continue
                                        }
                                    }

                                    val quality = s.optString("quality", "1080p")
                                    val kindStr = s.optString("kind", if (streamUrl.contains(".m3u8")) "hls" else "mp4")

                                    // Parse and cache worker-provided headers if present
                                    val hObj = s.optJSONObject("headers")
                                    if (hObj != null && streamUrl.isNotBlank()) {
                                        val hMap = mutableMapOf<String, String>()
                                        val hKeys = hObj.keys()
                                        while (hKeys.hasNext()) {
                                            val k = hKeys.next()
                                            val v = hObj.optString(k)
                                            if (v.isNotBlank()) hMap[k] = v
                                        }
                                        if (hMap.isNotEmpty()) {
                                            headersCache[streamUrl] = hMap
                                        }
                                    }

                                    // Parse and cache subtitles if returned
                                    val subsArray = s.optJSONArray("subtitles")
                                    if (subsArray != null && subsArray.length() > 0 && streamUrl.isNotBlank()) {
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
                                    if (qObj != null && streamUrl.isNotBlank()) {
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

                                    // Place streamUrl LAST so any '|' inside the URL is preserved with split(limit = 4)
                                    if (streamUrl.isNotBlank()) {
                                        val tVal = Regex("[?&]t=([0-9]+)").find(streamUrl)?.groupValues?.get(1)?.toLongOrNull()
                                        val isExpiredCdn = tVal != null && tVal > 1_000_000_000L && tVal < (System.currentTimeMillis() / 1000L)
                                        if (isExpiredCdn) {
                                            Log.w(TAG, "Skipping stream with expired CDN token (t=$tVal < now): $streamUrl")
                                        } else {
                                            serverList.add(
                                                ServerOption(
                                                    key = "turbo:$i|$quality|$kindStr|$streamUrl",
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
                    }
                }.onFailure { e ->
                    Log.w(TAG, "Fetching options from worker (type=$tryType) failed: ${e.message}")
                }

                if (streamsFound && serverList.isNotEmpty()) {
                    break
                }
            }

            if (streamsFound && serverList.isNotEmpty()) {
                break
            }
        }

        if (serverList.isEmpty()) {
            throw SourceException(
                kind = SourceErrorKind.NOT_FOUND,
                message = "No streams available from Animarvle Edge Worker",
            )
        }

        val byAudio = mutableMapOf<Audio, List<ServerOption>>()
        val isAnime = resolvedType.equals("anime", ignoreCase = true)

        if (isAnime) {
            val isDonghua = com.animarvle.app.AnimarvleApp.isDonghua(title)
            val origLang = if (isDonghua) "chinese" else "japanese"

            val explicitDub = serverList.filter { s ->
                val al = s.audioLanguage?.lowercase().orEmpty()
                val h = s.hint?.lowercase().orEmpty()
                val l = s.label.lowercase()
                al.contains("dub") ||
                    (al.isNotBlank() && !al.contains(origLang) && !al.contains("sub") && !al.contains("raw")) ||
                    h.contains("dub") ||
                    l.contains("dub")
            }
            val explicitSub = serverList.filter { it !in explicitDub }

            if (explicitSub.isNotEmpty()) {
                byAudio[Audio.SUB] = explicitSub
                byAudio[Audio.RAW] = explicitSub
            }
            // CRITICAL: Only populate Audio.DUB if real Dub streams exist so StreamResolver can fallback properly!
            if (explicitDub.isNotEmpty()) {
                byAudio[Audio.DUB] = explicitDub
            }
            if (byAudio.isEmpty()) {
                byAudio[Audio.SUB] = serverList
            }
        } else {
            byAudio[Audio.RAW] = serverList
            byAudio[Audio.SUB] = serverList
            byAudio[Audio.DUB] = serverList
        }

        EpisodeOptions(byAudio = byAudio)
    }

    // --- Resolve --------------------------------------------------------------------------------

    override suspend fun resolve(episode: SourceEpisode, choice: Choice): ResolvedMedia = withContext(Dispatchers.IO) {
        val parts = choice.serverKey.split("|", limit = 4)
        val quality = parts.getOrNull(1) ?: "1080p"

        // Support both new format (turbo:i|quality|kindStr|streamUrl) and legacy format (turbo:i|quality|streamUrl|kindStr)
        val part2 = parts.getOrNull(2).orEmpty()
        val part3 = parts.getOrNull(3).orEmpty()
        val (kindStr, streamUrl) = if (part3.startsWith("http://", ignoreCase = true) || part3.startsWith("https://", ignoreCase = true)) {
            part2 to part3
        } else {
            part3 to part2
        }

        if (streamUrl.isBlank()) {
            throw SourceException(SourceErrorKind.NOT_FOUND, "Invalid stream key: ${choice.serverKey}")
        }

        val isHls = kindStr.equals("hls", ignoreCase = true) || streamUrl.contains(".m3u8") || streamUrl.contains("/proxy/m3u8")
        val streamKind = if (isHls) StreamKind.HLS else StreamKind.PROGRESSIVE

        val cachedSubs = subtitlesCache[streamUrl] ?: emptyList()
        val qMap = qualitiesCache[streamUrl]

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
                        headers = buildHeadersForUrl(qUrl, streamUrl),
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
                    headers = buildHeadersForUrl(streamUrl, streamUrl),
                ),
            )
        }

        Log.i(TAG, "Playing Cloudflare stream with ${streams.size} qualities: $streamUrl ($quality, kind=$streamKind, subs=${cachedSubs.size})")

        ResolvedMedia(
            streams = streams,
            subtitles = cachedSubs,
            headers = buildHeadersForUrl(streamUrl, streamUrl),
        )
    }

    companion object {
        private const val TAG = "AnimarvleStreamSource"
        const val DEFAULT_WORKER_URL = "https://animarvle-edge.grandfootball21.workers.dev"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val MAX_CACHE_ENTRIES = 100

        private fun <K, V> createBoundedCache(): MutableMap<K, V> =
            Collections.synchronizedMap(
                object : LinkedHashMap<K, V>(MAX_CACHE_ENTRIES, 0.75f, true) {
                    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
                        size > MAX_CACHE_ENTRIES
                }
            )

        val subtitlesCache: MutableMap<String, List<SubtitleTrack>> = createBoundedCache()
        val qualitiesCache: MutableMap<String, Map<String, String>> = createBoundedCache()
        val headersCache: MutableMap<String, Map<String, String>> = createBoundedCache()
        val sizesCache: MutableMap<String, Long> = createBoundedCache()

        /**
         * Builds the exact required CDN headers for both ExoPlayer playback,
         * ChooseQualityBottomSheet size probing, and Media3 background downloading.
         */
        fun buildHeadersForUrl(url: String, primaryStreamUrl: String? = null): Map<String, String> {
            val workerHeaders = headersCache[url] ?: primaryStreamUrl?.let { headersCache[it] }
            return when {
                url.contains("hakunaymatata.com") || url.contains("bcdn.") || url.contains("acdn.") || url.contains("vcdn.") || url.contains("fcdn.") || url.contains("watch32.") -> mapOf(
                    "User-Agent" to "okhttp/4.12.0",
                    "Accept" to "*/*",
                )
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
                !workerHeaders.isNullOrEmpty() -> buildMap {
                    put("User-Agent", USER_AGENT)
                    put("Accept", "*/*")
                    putAll(workerHeaders)
                }
                url.contains("vidlink") -> mapOf(
                    "Origin" to "https://vidlink.pro",
                    "Referer" to "https://vidlink.pro/",
                    "Accept" to "*/*",
                )
                else -> mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "*/*",
                )
            }
        }
    }
}
