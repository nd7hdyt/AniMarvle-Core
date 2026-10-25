package com.animarvle.app.extensions

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
import com.animarvle.sourceapi.SubtitleTrack
import com.animarvle.sourceapi.SubFormat
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.Hoster
import com.animarvle.app.ui.player.SpokenLanguage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeImpl
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.SEpisodeImpl
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.animarvle.domain.DerivedUi
import java.util.concurrent.ConcurrentHashMap
import com.animarvle.app.AnimarvleApp

/**
 * Adapts a loaded Aniyomi/Mihon [AnimeSource] onto our own plugin contract (`MediaSource`,
 * ARCH §5) so the rest of the app never learns that the provider is an Aniyomi extension.
 *
 * ## Identity across calls
 * Our contract passes immutable value objects between calls, but the Aniyomi side needs the live
 * [SAnime]/[SEpisode] objects. Rather than leak Aniyomi types into `:source-api`, we carry the
 * upstream keys in `extra` (`sAnimeUrl`, `episodeUrl`) and keep a small process-lifetime cache of
 * the resolved model objects. A miss is re-fetched through the source itself, so the flow stays
 * correct even across process restarts or a cold cache.
 *
 * ## Servers
 * Aniyomi models playback as `SEpisode -> List<Hoster> -> List<Video> -> getVideoUrl`. We map
 * Hoster -> [ServerOption] (the dynamic truth per ARCH §5) and resolve the chosen hoster's videos
 * into [Stream]s, one per quality.
 */
class TachiyomiAnimeSource(
    private val source: AnimeSource,
) : MediaSource {

    override val id: Long get() = source.id
    override val name: String get() = source.name
    override val language: String get() = source.lang

    /** Aniyomi anime extensions carry both series and anime movies. */
    override val supportedTypes: Set<MediaType> = setOf(MediaType.ANIME, MediaType.MOVIE)

    override val capabilities: SourceCapabilities = SourceCapabilities(
        sub = true,
        // Heuristic, never trusted for behaviour (ARCH §6): dub tracks are source-specific and are
        // reported through the resolved streams rather than assumed here.
        dub = source.lang.equals("en", ignoreCase = true),
        multiServer = true,
        qualities = true,
        skipTimes = true,
        downloadable = false,
    )

    private val animeCache = ConcurrentHashMap<String, SAnime>()
    private val episodeCache = ConcurrentHashMap<String, SEpisode>()
    private val videosCache = ConcurrentHashMap<String, List<Video>>()

    override suspend fun search(query: String, type: MediaType, page: Int): List<SourceMedia> = withContext(Dispatchers.IO) {
        val catalogue = source as? AnimeCatalogueSource
            ?: throw SourceException(
                SourceErrorKind.UNSUPPORTED,
                "${source.name} is not a catalogue source; it cannot be searched",
            )
        val results = catalogue.getSearchAnime(page, query, AnimeFilterList()).animes
        results.forEach { animeCache[it.url] = it }
        results.map { it.toSourceMedia(type) }
    }

    override suspend fun episodes(media: SourceMedia): List<SourceEpisode> = withContext(Dispatchers.IO) {
        val anime = animeFor(media)
        val sEpisodes = runCatching {
            source.getEpisodeList(anime)
        }.recoverCatching { err ->
            if (err is UnsupportedOperationException) {
                // Extensions implementing newer extensions-lib 17 use getAnimeEpisodeUpdate
                source.getAnimeEpisodeUpdate(anime, emptyList(), fetchDetails = false, fetchEpisodes = true).episodes
            } else {
                throw err
            }
        }.getOrThrow()

        sEpisodes.map { episode ->
            episodeCache[episodeCacheKey(anime.url, episode.url)] = episode
            SourceEpisode(
                url = episode.url,
                number = episode.episode_number,
                title = episode.name.takeIf { it.isNotBlank() },
                thumbnail = null,
                isFiller = episode.fillermark,
                extra = mapOf(
                    KEY_ANIME_URL to anime.url,
                    KEY_EPISODE_URL to episode.url,
                    "animeTitle" to anime.title,
                ),
            )
        }
    }

    override suspend fun options(episode: SourceEpisode): EpisodeOptions = withContext(Dispatchers.IO) {
        val anime = animeFor(episode.extra)
        val sEpisode = episodeFor(anime, episode)

        // Most Keiyoushi extensions expose hosters; a minority only implement the direct
        // `getVideoList(episode)` path. Try hosters first, then degrade to one synthetic server.
        val hosters = runCatching { hostersOf(sEpisode) }
            .onFailure { android.util.Log.e(TAG, "${source.name}: no hoster list failed", it) }
            .getOrNull()
            .orEmpty()

        android.util.Log.i(TAG, "${source.name}: fetched ${hosters.size} hosters: ${hosters.map { it.hosterName }}")

        // Detect if the anime title itself is a Dub variant (e.g. "One Piece (Dub)")
        val animeTitle = episode.extra["animeTitle"].orEmpty().lowercase()
        val isDonghuaMedia = AnimarvleApp.isDonghua(animeTitle) || source.name.contains("anichi", ignoreCase = true)
        val origLang = if (isDonghuaMedia) "Chinese" else "Japanese"

        val allServers = if (hosters.isNotEmpty()) {
            hosters.mapIndexed { idx, h ->
                val rawName = h.hosterName.ifEmpty { source.name }
                val detectedLang = SpokenLanguage.fromRawTag(rawName)?.displayName ?: when {
                    rawName.contains("chinese", ignoreCase = true) || rawName.contains("mandarin", ignoreCase = true) || rawName.contains("[zh]", ignoreCase = true) || rawName.contains("donghua", ignoreCase = true) || rawName.contains("c-drama", ignoreCase = true) -> "Chinese"
                    rawName.contains("korean", ignoreCase = true) || rawName.contains("[ko]", ignoreCase = true) || rawName.contains("k-drama", ignoreCase = true) -> "Korean"
                    rawName.contains("hindi", ignoreCase = true) || rawName.contains("[hi]", ignoreCase = true) -> "Hindi"
                    rawName.contains("spanish", ignoreCase = true) || rawName.contains("[es]", ignoreCase = true) -> "Spanish"
                    rawName.contains("russian", ignoreCase = true) || rawName.contains("[ru]", ignoreCase = true) -> "Russian"
                    rawName.contains("portuguese", ignoreCase = true) || rawName.contains("[pt]", ignoreCase = true) -> "Portuguese"
                    rawName.contains("arabic", ignoreCase = true) || rawName.contains("[ar]", ignoreCase = true) -> "Arabic"
                    rawName.contains("french", ignoreCase = true) || rawName.contains("[fr]", ignoreCase = true) -> "French"
                    rawName.contains("german", ignoreCase = true) || rawName.contains("[de]", ignoreCase = true) -> "German"
                    rawName.contains("italian", ignoreCase = true) || rawName.contains("[it]", ignoreCase = true) -> "Italian"
                    rawName.contains("japanese", ignoreCase = true) || rawName.contains("[ja]", ignoreCase = true) || rawName.contains("raw", ignoreCase = true) -> "Japanese"
                    rawName.contains("kannada", ignoreCase = true) || rawName.contains("[kn]", ignoreCase = true) -> "Kannada"
                    rawName.contains("malayalam", ignoreCase = true) || rawName.contains("[ml]", ignoreCase = true) -> "Malayalam"
                    rawName.contains("tamil", ignoreCase = true) || rawName.contains("[ta]", ignoreCase = true) -> "Tamil"
                    rawName.contains("telugu", ignoreCase = true) || rawName.contains("[te]", ignoreCase = true) -> "Telugu"
                    rawName.contains("english", ignoreCase = true) || rawName.contains("[en]", ignoreCase = true) -> "English"
                    else -> null
                }
                val fallbackLang = when {
                    isDonghuaMedia -> "Chinese"
                    source.name.contains("kisskh", ignoreCase = true) && animeTitle.contains("k-drama", ignoreCase = true) -> "Korean"
                    else -> null
                }
                ServerOption(key = "idx:$idx", label = rawName, audioLanguage = detectedLang ?: fallbackLang)
            }
        } else {
            val defaultSyntheticLang = if (isDonghuaMedia) "Chinese" else null
            listOf(ServerOption(key = SYNTHETIC_SERVER, label = source.name, audioLanguage = defaultSyntheticLang))
        }

        val isDubMedia = animeTitle.contains("(dub)") || animeTitle.contains(" dub") || animeTitle.contains("[dub]")
        val isSourceDubSpecialized = source.name.contains("dub", ignoreCase = true)

        // Anikoto, AnimeKai, AniWave, etc. provide multiple hosters with identical names
        // (e.g. HD-1, Vidstream-2, Vidstream-1, HD-2, HD-1, Vidstream-2, Vidstream-1, HD-2) where the
        // first appearance corresponds to Sub and the second appearance corresponds to Dub.
        // Differentiate them clearly so the user sees all servers, labeled with their respective audio.
        val subHosters = mutableListOf<ServerOption>()
        val dubHosters = mutableListOf<ServerOption>()

        val occurrences = mutableMapOf<String, Int>()
        val hasDuplicates = hosters.groupBy { it.hosterName.ifEmpty { source.name } }.any { it.value.size > 1 }

        if (hasDuplicates) {
            android.util.Log.i(TAG, "${source.name}: detected duplicate hosters, partitioning by occurrence")
            hosters.forEachIndexed { idx, h ->
                val base = h.hosterName.ifEmpty { source.name }
                val count = occurrences.getOrDefault(base, 0)
                occurrences[base] = count + 1
                if (count == 0) {
                    subHosters.add(ServerOption(key = "idx:$idx", label = "$base (Sub)", audioLanguage = allServers.getOrNull(idx)?.audioLanguage ?: origLang))
                } else {
                    dubHosters.add(ServerOption(key = "idx:$idx", label = "$base (Dub)", audioLanguage = allServers.getOrNull(idx)?.audioLanguage ?: "English"))
                }
            }
        } else {
            val explicitDub = allServers.filter { s ->
                val l = s.label.lowercase()
                l.contains("dub") || (s.audioLanguage != null && !s.audioLanguage.equals(origLang, ignoreCase = true))
            }
            val explicitSub = allServers.filter { s ->
                val l = s.label.lowercase()
                l.contains("sub") || s.audioLanguage.equals(origLang, ignoreCase = true)
            }

            if (explicitDub.isNotEmpty() || explicitSub.isNotEmpty()) {
                dubHosters.addAll(explicitDub.ifEmpty { allServers })
                subHosters.addAll(explicitSub.ifEmpty { allServers })
            } else {
                dubHosters.addAll(allServers)
                subHosters.addAll(allServers)
            }
        }

        val dubServers = when {
            isDubMedia || isSourceDubSpecialized -> allServers
            else -> dubHosters
        }

        val subServers = when {
            isDubMedia -> emptyList() // Dub-only release has no sub
            else -> subHosters
        }

        EpisodeOptions(
            byAudio = buildMap {
                // If both sub and dub are available, provide both
                if (subServers.isNotEmpty()) put(Audio.SUB, subServers)
                if (dubServers.isNotEmpty()) put(Audio.DUB, dubServers)
                // Also provide full combined list under the active audio if needed,
                // or ensure both are present
                if (isEmpty()) {
                    put(Audio.SUB, allServers)
                    put(Audio.DUB, allServers)
                }
            },
        )
    }

    override suspend fun resolve(episode: SourceEpisode, choice: Choice): ResolvedMedia = withContext(Dispatchers.IO) {
        val anime = animeFor(episode.extra)
        val sEpisode = episodeFor(anime, episode)

        val videos = if (choice.serverKey == SYNTHETIC_SERVER) {
            videosCache.getOrPut(videosKey(episode.extra, SYNTHETIC_SERVER)) {
                runCatching { sourceAsHttp()?.getVideoList(sEpisode) }.getOrNull() ?: emptyList()
            }
        } else {
            val hosters = hostersOf(sEpisode)
            // If the key is an index-based key (idx:N), look up by position directly.
            // Otherwise fall back to URL matching for backward-compatibility.
            val hoster = if (choice.serverKey.startsWith("idx:")) {
                val idx = choice.serverKey.removePrefix("idx:").toIntOrNull()
                if (idx != null && idx in hosters.indices) hosters[idx] else null
            } else {
                hosters.firstOrNull { (it.hosterUrl.ifEmpty { it.hosterName }) == choice.serverKey }
            }
            android.util.Log.d(TAG, "${source.name}: resolving serverKey='${choice.serverKey}' audio=${choice.audio} -> hoster=${hoster?.hosterName}")
            if (hoster != null) {
                hoster.videoList ?: runCatching { sourceAsHttp()?.getVideoList(hoster) }.getOrNull().orEmpty()
            } else {
                runCatching { sourceAsHttp()?.getVideoList(sEpisode) }.getOrNull().orEmpty()
            }
        }
        videosCache[videosKey(episode.extra, choice.serverKey)] = videos

        val defaultHeaders = runCatching { sourceAsHttp()?.headers?.toMap().orEmpty() }.getOrDefault(emptyMap())
        val streams = videos.mapNotNull { video ->
            val resolvedVideo = runCatching { sourceAsHttp()?.resolveVideo(video) }.getOrNull() ?: video
            val url = runCatching {
                sourceAsHttp()?.getVideoUrl(resolvedVideo)?.takeIf { it.isNotBlank() } ?: resolvedVideo.videoUrl
            }.getOrDefault(resolvedVideo.videoUrl)
            if (url.isBlank() || url.equals("null", ignoreCase = true)) return@mapNotNull null
            val videoHeaders = resolvedVideo.headers?.toMap().orEmpty()
            val mergedHeaders = defaultHeaders + videoHeaders
            val rawTitle = resolvedVideo.videoTitle.trim()
            val parsedHeight = resolvedVideo.resolution ?: when {
                rawTitle.contains("2160") || rawTitle.contains("4k", ignoreCase = true) -> 2160
                rawTitle.contains("1080") -> 1080
                rawTitle.contains("720") -> 720
                rawTitle.contains("480") -> 480
                rawTitle.contains("360") -> 360
                else -> null
            }
            val cleanQuality = DerivedUi.sanitizeQualityLabel(rawTitle.takeIf { it.isNotBlank() } ?: parsedHeight?.let { "${it}p" })
            android.util.Log.i(TAG, "${source.name} stream: label='$cleanQuality' (raw='$rawTitle'), url='$url', kind=${streamKindOf(url)}")
            Stream(
                url = url,
                kind = streamKindOf(url),
                qualityLabel = cleanQuality,
                height = parsedHeight,
                bitrate = resolvedVideo.bitrate,
                headers = mergedHeaders,
            )
        }
        if (streams.isEmpty()) {
            throw SourceException(
                SourceErrorKind.NOT_FOUND,
                "${source.name} returned no playable server for episode ${episode.number}",
            )
        }

        val sortedStreams = if (choice.audio == Audio.DUB) {
            streams.sortedByDescending {
                val label = it.qualityLabel.orEmpty().lowercase()
                val u = it.url.lowercase()
                label.contains("dub") || label.contains("en") || u.contains("dub") || u.contains("en")
            }
        } else {
            streams.sortedByDescending {
                val label = it.qualityLabel.orEmpty().lowercase()
                val u = it.url.lowercase()
                label.contains("sub") || label.contains("jap") || u.contains("sub") || u.contains("jap")
            }
        }

        val streamHeaders = sortedStreams.firstOrNull()?.headers ?: defaultHeaders
        ResolvedMedia(
            streams = sortedStreams,
            subtitles = subtitleTracks(videos),
            skipTimes = skipTimes(videos),
            headers = streamHeaders,
        )
    }

    // --- model mapping -------------------------------------------------------------------

    private fun SAnime.toSourceMedia(type: MediaType = MediaType.ANIME): SourceMedia {
        val extractedYear = parseYear(title) ?: parseYearFromMemo(memo)
        return SourceMedia(
            url = url,
            title = title,
            year = extractedYear,
            episodeCount = null,
            poster = thumbnail_url,
            type = type,
            extra = mapOf(
                KEY_ANIME_URL to url,
                "animeTitle" to title,
            ),
        )
    }

    private fun parseYear(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val match = YEAR_REGEX.find(text) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    private fun parseYearFromMemo(memo: kotlinx.serialization.json.JsonObject?): Int? {
        if (memo == null) return null
        val yearStr = (memo["year"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: (memo["aired_year"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        return yearStr?.toIntOrNull()
    }

    private fun audioFor(): Audio = Audio.SUB

    /** `createPackageContext`-free access to the HTTP-only entry points. */
    private fun sourceAsHttp() = source as? eu.kanade.tachiyomi.animesource.online.AnimeHttpSource

    private suspend fun hostersOf(episode: SEpisode): List<Hoster> =
        sourceAsHttp()?.getHosterList(episode).orEmpty()

    private fun streamKindOf(url: String): StreamKind = when {
        url.contains(".m3u8", ignoreCase = true) ||
            url.contains("/hls/", ignoreCase = true) ||
            url.contains("m3u8", ignoreCase = true) ||
            url.contains("master", ignoreCase = true) ||
            url.contains("playlist", ignoreCase = true) -> StreamKind.HLS
        url.contains(".mpd", ignoreCase = true) ||
            url.contains("mp4-dash", ignoreCase = true) ||
            url.contains("/dash/", ignoreCase = true) -> StreamKind.DASH
        else -> StreamKind.PROGRESSIVE
    }

    private fun subtitleTracks(videos: List<Video>): List<SubtitleTrack> =
        videos.flatMap { video ->
            video.subtitleTracks.map { track ->
                SubtitleTrack(
                    url = track.url,
                    language = track.lang,
                    label = track.lang,
                    format = if (track.url.endsWith(".ass", ignoreCase = true)) {
                        SubFormat.ASS
                    } else {
                        SubFormat.VTT
                    },
                )
            }
        }.distinctBy { it.url }

    private fun skipTimes(videos: List<Video>): SkipTimes? {
        val stamps = videos.firstOrNull()?.timestamps.orEmpty()
        val opening = stamps.firstOrNull { it.type == eu.kanade.tachiyomi.animesource.model.ChapterType.Opening }
        val ending = stamps.firstOrNull { it.type == eu.kanade.tachiyomi.animesource.model.ChapterType.Ending }
        return if (opening == null && ending == null) {
            null
        } else {
            SkipTimes(
                opening = opening?.let { (it.start * 1000).toLong()..(it.end * 1000).toLong() },
                ending = ending?.let { (it.start * 1000).toLong()..(it.end * 1000).toLong() },
            )
        }
    }

    // --- identity / cache plumbing --------------------------------------------------------

    private fun animeFor(media: SourceMedia): SAnime = animeFor(
        media.extra.toMutableMap().apply {
            if (!containsKey(KEY_ANIME_URL) && media.url.isNotBlank()) {
                put(KEY_ANIME_URL, media.url)
            }
            if (!containsKey("animeTitle") && media.title.isNotBlank()) {
                put("animeTitle", media.title)
            }
        },
    )

    private fun animeFor(extra: Map<String, String>): SAnime {
        val url = extra[KEY_ANIME_URL] ?: extra["mediaUrl"]
            ?: animeCache.values.lastOrNull()?.url
            ?: throw SourceException(SourceErrorKind.NOT_FOUND, "Missing $KEY_ANIME_URL in source payload")
        return animeCache[url] ?: SAnimeImpl().also {
            it.url = url
            it.title = extra["animeTitle"].orEmpty()
        }.let { animeCache.getOrPut(url) { it } }
    }

    private fun episodeFor(anime: SAnime, episode: SourceEpisode): SEpisode {
        val epUrl = episode.url.ifBlank {
            episodeCache.keys.firstOrNull { it.startsWith(anime.url) }?.substringAfter("#")
                ?: episode.url
        }
        val key = episodeCacheKey(anime.url, epUrl)
        return episodeCache[key] ?: SEpisodeImpl().also {
            it.url = epUrl
            it.episode_number = episode.number
            it.name = episode.title.orEmpty()
        }.let { episodeCache.getOrPut(key) { it } }
    }

    private fun episodeCacheKey(animeUrl: String, episodeUrl: String) = "$animeUrl#$episodeUrl"

    private fun videosKey(extra: Map<String, String>, serverKey: String) =
        "${extra[KEY_EPISODE_URL]}|$serverKey"

    companion object {
        const val TAG = "TachiyomiSource"

        /** `SourceMedia.extra` key holding the upstream Aniyomi anime URL. */
        const val KEY_ANIME_URL = "sAnimeUrl"
        const val KEY_EPISODE_URL = "episodeUrl"
        const val SYNTHETIC_SERVER = "__direct__"
        private val YEAR_REGEX = Regex("""(?:\(|\[|\b)(19\d{2}|20\d{2})(?:\)|\]|\b)""")
    }
}