package com.animarvle.sourcesample

import com.animarvle.sourceapi.Audio
import com.animarvle.sourceapi.Choice
import com.animarvle.sourceapi.EpisodeOptions
import com.animarvle.sourceapi.MediaSource
import com.animarvle.sourceapi.MediaType
import com.animarvle.sourceapi.ResolvedMedia
import com.animarvle.sourceapi.SkipTimes
import com.animarvle.sourceapi.SourceCapabilities
import com.animarvle.sourceapi.SourceErrorKind
import com.animarvle.sourceapi.SourceEpisode
import com.animarvle.sourceapi.SourceException
import com.animarvle.sourceapi.SourceMedia
import com.animarvle.sourceapi.Stream
import com.animarvle.sourceapi.StreamKind
import com.animarvle.sourceapi.SubFormat
import com.animarvle.sourceapi.SubtitleTrack

/**
 * FakeSource (ARCH §2 :source-sample). A fixture-driven plugin with no network access:
 * every catalog, server and failure mode is declared in memory so the resolver acceptance
 * tests (PROMPT §10) and the on-device Player have something deterministic to play.
 *
 * PROMPT §3.3: the repo ships only fixture/local sources. No real streaming host appears here.
 */
class FakeSource(
    override val id: Long = 1L,
    override val name: String = "Fake source (fixtures)",
    override val language: String = "en",
    override val supportedTypes: Set<MediaType> = setOf(MediaType.ANIME, MediaType.MOVIE),
    override val capabilities: SourceCapabilities = SourceCapabilities(
        sub = true,
        dub = true,
        multiServer = true,
        qualities = true,
        skipTimes = true,
        downloadable = true,
    ),
    private val catalog: List<SourceMedia> = DEFAULT_CATALOG,
) : MediaSource {

    /** Servers that should throw/timeout, keyed by episode url -> server key. */
    val failingServers: MutableMap<String, Set<String>> = mutableMapOf()

    /** Which audio keys this source actually offers (RAW-only for the RAW fixture). */
    var audioKeys: Set<Audio> = setOf(Audio.SUB, Audio.DUB)

    /** Extra subtitle tracks attached to every resolved media. */
    var subtitleTracks: List<SubtitleTrack> = listOf(
        SubtitleTrack("fixture://subs/en.vtt", "en", "English", SubFormat.VTT),
        SubtitleTrack("fixture://subs/es.vtt", "es", "Español", SubFormat.VTT),
    )

    /** When true the source advertises one HLS master (adaptive) instead of flat variants. */
    var adaptiveMaster: Boolean = false

    /**
     * Real playable streams for on-device demos. Tests leave this null and use the
     * deterministic fixture:// URLs; the app injects a public sample so the Player has
     * something to actually play. Never a streaming-service host (PROMPT §3.3).
     */
    var streamOverride: List<Stream>? = null

    /** HTTP headers a real source would need. Empty for the public demo stream. */
    var responseHeaders: Map<String, String> = mapOf("Referer" to "https://fixture.invalid/")

    override suspend fun search(query: String, type: MediaType, page: Int): List<SourceMedia> =
        catalog.filter {
            it.type == type && it.title.contains(query.trim(), ignoreCase = true)
        }

    override suspend fun episodes(media: SourceMedia): List<SourceEpisode> {
        val total = media.episodeCount ?: 1
        if (media.type == MediaType.MOVIE) {
            // A movie is exactly one playable unit (ARCH §10 test 10).
            return listOf(SourceEpisode(url = "${media.url}/ep-1", number = 1f, title = media.title))
        }
        return (1..total).map { n ->
            SourceEpisode(
                url = "${media.url}/ep-$n",
                number = n.toFloat(),
                title = "Episode $n",
                isFiller = false,
            )
        }
    }

    override suspend fun options(episode: SourceEpisode): EpisodeOptions {
        val servers = serverList()
        val byAudio = audioKeys.associateWith { servers }
        return EpisodeOptions(byAudio)
    }

    override suspend fun resolve(episode: SourceEpisode, choice: Choice): ResolvedMedia {
        val dead = failingServers[episode.url].orEmpty()
        if (choice.serverKey in dead) {
            throw SourceException(
                SourceErrorKind.SERVER_ERROR,
                "FakeSource: server ${choice.serverKey} is marked as failing",
            )
        }
        if (choice.audio !in audioKeys) {
            throw SourceException(
                SourceErrorKind.UNSUPPORTED,
                "FakeSource: ${choice.audio} is not offered",
            )
        }

        val streams = streamOverride ?: if (adaptiveMaster) {
            listOf(Stream("$BASE/stream/master.m3u8", StreamKind.HLS))
        } else {
            listOf(
                Stream("$BASE/stream/1080.mp4", StreamKind.PROGRESSIVE, "1080p", 1080),
                Stream("$BASE/stream/720.mp4", StreamKind.PROGRESSIVE, "720p", 720),
                Stream("$BASE/stream/360.mp4", StreamKind.PROGRESSIVE, "360p", 360),
            )
        }

        return ResolvedMedia(
            streams = streams,
            subtitles = subtitleTracks,
            skipTimes = SkipTimes(opening = 0L..90_000L, ending = 1_380_000L..1_440_000L),
            headers = responseHeaders,
        )
    }

    private fun serverList() = listOf(
        com.animarvle.sourceapi.ServerOption("mirror-a", "Mirror A", hint = "Primary"),
        com.animarvle.sourceapi.ServerOption("mirror-b", "Mirror B", hint = "Backup"),
        com.animarvle.sourceapi.ServerOption("mirror-c", "Mirror C", hint = "Backup"),
    )

    companion object {
        /** Fixture base. NOT a real host - it never resolves (PROMPT §3.3). */
        const val BASE = "fixture://animarvle"

        val DEFAULT_CATALOG: List<SourceMedia> = listOf(
            SourceMedia(
                url = "$BASE/media/death-note",
                title = "Death Note",
                year = 2006,
                episodeCount = 37,
                poster = null,
                type = MediaType.ANIME,
            ),
            SourceMedia(
                url = "$BASE/media/one-piece",
                title = "ONE PIECE",
                year = 1999,
                episodeCount = 1100,
                poster = null,
                type = MediaType.ANIME,
            ),
            SourceMedia(
                url = "$BASE/media/naruto",
                title = "Naruto",
                year = 2002,
                episodeCount = 220,
                poster = null,
                type = MediaType.ANIME,
            ),
            // Movie fixtures share a title but differ by year (PROMPT §10 test 12).
            SourceMedia("$BASE/movie/tears-of-steel-2009", "Tears of Steel", 2009, 1, null, MediaType.MOVIE),
            SourceMedia("$BASE/movie/tears-of-steel-2012", "Tears of Steel", 2012, 1, null, MediaType.MOVIE),
            SourceMedia("$BASE/movie/big-buck-bunny", "Big Buck Bunny", 2008, 1, null, MediaType.MOVIE),
        )
    }
}