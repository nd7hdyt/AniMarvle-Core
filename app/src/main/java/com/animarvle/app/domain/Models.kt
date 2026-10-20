package com.animarvle.app.domain

/**
 * UI-facing media models (ARCH §4). Source-independent: the screen never sees
 * AniList or TMDB types.
 *
 * NOTE: [com.animarvle.sourceapi.MediaType] is the canonical MediaType and lives in
 * :source-api so plugins share it. Only the AiringStatus enum is defined here because
 * it is metadata-provider-agnostic and UI-facing only.
 */

enum class AiringStatus { FINISHED, RELEASING, NOT_YET_RELEASED, CANCELLED, HIATUS, UNKNOWN }

data class MediaUi(
    val id: Long,
    val title: String,
    val romajiTitle: String? = null,
    val nativeTitle: String? = null,
    val synonyms: List<String> = emptyList(),
    val synopsis: String?,
    val coverUrl: String?,
    val bannerUrl: String?,
    val genres: List<String>,
    val score: Double?,
    val year: Int?,
    val startDateIso: String?,
    val format: String?,
    val episodes: Int?,
    val durationMinutes: Int?,
    val status: AiringStatus,
    val nextAiringEpisode: Int?,
    val nextAiringAtEpochSec: Long?,
    val seasons: List<SeasonUi> = emptyList(),
)

data class SeasonUi(
    val id: Long,
    val title: String,
    val posterUrl: String?,
    val isCurrent: Boolean = false,
    val seasonNumber: Int? = null,
)

data class ScheduleItem(
    val mediaId: Long,
    val title: String,
    val coverUrl: String?,
    val episode: Int?,
    val airingAtEpochSec: Long,
    val format: String?,
    val totalEpisodes: Int?,
)