package com.animarvle.sourceapi

/**
 * The source-plugin contract (ARCH §5).
 *
 * Pure Kotlin, no Android types: a plugin only depends on this module.
 * Plugins declare which [SOURCE_API_VERSION] they target; the host rejects unknown majors.
 */

const val SOURCE_API_VERSION = 1

/** Titles as reported by one provider. At least one field is normally present. */
data class Titles(
    val english: String? = null,
    val romaji: String? = null,
    val native: String? = null,
)

enum class StreamKind { HLS, DASH, PROGRESSIVE }

enum class SubFormat { VTT, SRT, ASS }

data class SourceMedia(
    val url: String,
    val title: String,
    val year: Int?,
    val episodeCount: Int?,
    val poster: String?,
    val type: MediaType,
    val extra: Map<String, String> = emptyMap(),
)

data class SourceEpisode(
    val url: String,
    val number: Float,
    val title: String? = null,
    val thumbnail: String? = null,
    val isFiller: Boolean = false,
    val extra: Map<String, String> = emptyMap(),
)

/** One server actually available for a specific episode under a given audio track. */
data class ServerOption(
    val key: String,
    val label: String,
    val hint: String? = null,
    val audioLanguage: String? = null,
)

/** THE DYNAMIC TRUTH: audio -> ordered servers available for THIS episode. */
data class EpisodeOptions(val byAudio: Map<Audio, List<ServerOption>>)

data class Choice(val audio: Audio, val serverKey: String)

data class Stream(
    val url: String,
    val kind: StreamKind,
    val qualityLabel: String? = null,
    val height: Int? = null,
    val bitrate: Int? = null,
    val headers: Map<String, String> = emptyMap(),
)

data class SubtitleTrack(
    val url: String,
    val language: String,
    val label: String,
    val format: SubFormat,
)

/** Opening/ending ranges in milliseconds; the host renders Skip buttons from these. */
data class SkipTimes(val opening: LongRange? = null, val ending: LongRange? = null)

data class ResolvedMedia(
    val streams: List<Stream>,
    val subtitles: List<SubtitleTrack> = emptyList(),
    val skipTimes: SkipTimes? = null,
    val headers: Map<String, String> = emptyMap(),
)

/** Static hints for the Sources screen only. Never trusted for behavior (ARCH §6). */
data class SourceCapabilities(
    val sub: Boolean,
    val dub: Boolean,
    val multiServer: Boolean,
    val qualities: Boolean,
    val skipTimes: Boolean,
    val downloadable: Boolean,
)

/** Why a source refused to produce a playable stream (ARCH §5: throw, never a dead stream). */
enum class SourceErrorKind {
    NOT_FOUND,
    TIMEOUT,
    NETWORK,
    UNSUPPORTED,
    SERVER_ERROR,
    UNKNOWN,
}

class SourceException(
    val kind: SourceErrorKind,
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * A source plugin. Implementations are expected to be stateless with respect to the
 * caller: [options] and [resolve] must be idempotent and cancellable, and the host
 * applies its own timeouts.
 */
interface MediaSource {
    val id: Long
    val name: String
    val language: String
    val supportedTypes: Set<MediaType>
    val capabilities: SourceCapabilities

    /** 1) Find the title in this source. Used by SourceMatcher with metadata titles/synonyms/year. */
    suspend fun search(query: String, type: MediaType, page: Int = 1): List<SourceMedia>

    /** 2) Episodes for a matched entry. A movie is a single episode with number = 1. */
    suspend fun episodes(media: SourceMedia): List<SourceEpisode>

    /** 3) What can be played for this episode right now. */
    suspend fun options(episode: SourceEpisode): EpisodeOptions

    /** 4) Resolve one concrete choice to playable streams. */
    suspend fun resolve(episode: SourceEpisode, choice: Choice): ResolvedMedia
}