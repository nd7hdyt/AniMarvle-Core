package com.animarvle.app.data.anilist

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire models for the AniList GraphQL API.
 * Field shapes were verified against the live API on 2026-10-11 (see docs/VERSIONS.md).
 * These are transport types only; the UI never sees them (ARCH §10).
 */

@Serializable
data class GqlRequest(val query: String, val variables: Map<String, String> = emptyMap())

@Serializable
data class GqlResponse<T>(
    val data: T? = null,
    val errors: List<GqlError>? = null,
)

@Serializable
data class GqlError(val message: String? = null, val status: Int? = null)

@Serializable
data class MediaTitle(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
)

@Serializable
data class CoverImage(
    @SerialName("extraLarge") val extraLarge: String? = null,
    val large: String? = null,
    val color: String? = null,
)

@Serializable
data class FuzzyDate(val year: Int? = null, val month: Int? = null, val day: Int? = null)

@Serializable
data class NextAiring(
    val episode: Int? = null,
    val airingAt: Long? = null,
    val timeUntilAiring: Long? = null,
)

@Serializable
data class AiringNode(
    val airingAt: Long = 0,
    val episode: Int? = null,
    val media: Media? = null,
)

@Serializable
data class Media(
    val id: Long,
    val idMal: Long? = null,
    val title: MediaTitle? = null,
    val synonyms: List<String>? = null,
    val countryOfOrigin: String? = null,
    val status: String? = null,
    val format: String? = null,
    val episodes: Int? = null,
    val season: String? = null,
    val seasonYear: Int? = null,
    val startDate: FuzzyDate? = null,
    @SerialName("coverImage") val coverImage: CoverImage? = null,
    val bannerImage: String? = null,
    val genres: List<String>? = null,
    val averageScore: Int? = null,
    val description: String? = null,
    val duration: Int? = null,
    val isAdult: Boolean? = null,
    @SerialName("nextAiringEpisode") val nextAiringEpisode: NextAiring? = null,
    val recommendations: RecommendationConnection? = null,
    val relations: RelationConnection? = null,
)

@Serializable
data class RecommendationNode(
    val rating: Int? = null,
    val mediaRecommendation: Media? = null,
)

@Serializable
data class RecommendationConnection(
    val nodes: List<RecommendationNode> = emptyList(),
)

@Serializable
data class RelationEdge(
    val relationType: String? = null,
    val node: Media? = null,
)

@Serializable
data class RelationConnection(
    val edges: List<RelationEdge> = emptyList(),
)

@Serializable
data class PageInfo(
    val currentPage: Int? = null,
    @SerialName("hasNextPage") val hasNextPage: Boolean? = null,
    val total: Int? = null,
)

@Serializable
data class MediaPage(
    val media: List<Media> = emptyList(),
    val pageInfo: PageInfo? = null,
)

@Serializable
data class SchedulePage(
    @SerialName("airingSchedules") val airingSchedules: List<AiringNode> = emptyList(),
)

@Serializable
data class TrendingData(val Page: MediaPage? = null)

@Serializable
data class ScheduleData(val Page: SchedulePage? = null)

@Serializable
data class MediaData(val Media: Media? = null)