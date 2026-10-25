package com.animarvle.domain

import com.animarvle.sourceapi.MediaSource
import com.animarvle.sourceapi.MediaType
import com.animarvle.sourceapi.SourceMedia
import com.animarvle.sourceapi.Titles

/** What the matcher needs from the metadata provider (ARCH §4). */
data class MetaQuery(
    val metaId: String,
    val type: MediaType,
    val titles: Titles,
    val synonyms: List<String> = emptyList(),
    val year: Int? = null,
    val episodeCount: Int? = null,
    val requestedAudio: com.animarvle.sourceapi.Audio = com.animarvle.sourceapi.Audio.SUB,
)

data class MatchCandidate(
    val media: SourceMedia,
    val score: Double,
    val reasons: List<String>,
    val source: MediaSource? = null,
)

sealed interface MatchResult {
    data class Matched(
        val sourceId: Long,
        val sourceName: String,
        val media: SourceMedia,
        val score: Double,
    ) : MatchResult

    data object NoMatch : MatchResult
}

/**
 * SourceMatcher: metadata entry -> source entry (ARCH §7.1).
 *
 * Anime: normalized-title similarity across all title variants, + year, + episode count, + type.
 * Movies: the YEAR is the strongest signal so remakes with identical titles stay separate
 * (PROMPT §10 test 12); non-MOVIE candidates are rejected outright.
 */
class SourceMatcher(
    private val threshold: Double = DEFAULT_THRESHOLD,
) {

    suspend fun match(meta: MetaQuery, sources: List<MediaSource>): MatchResult {
        val queries = buildList {
            val baseTitles = buildList {
                meta.titles.english?.takeIf { it.isNotBlank() }?.let { add(it) }
                meta.titles.romaji?.takeIf { it.isNotBlank() }?.let { add(it) }
                meta.synonyms.take(MAX_SYNONYMS).forEach { s -> if (s.isNotBlank()) add(s) }
                meta.titles.native?.takeIf { it.isNotBlank() }?.let { add(it) }
            }.distinct()

            if (meta.requestedAudio == com.animarvle.sourceapi.Audio.DUB && meta.type == com.animarvle.sourceapi.MediaType.ANIME) {
                meta.titles.english?.takeIf { it.isNotBlank() }?.let {
                    add("$it (Dub)")
                    add("$it Dub")
                }
                meta.titles.romaji?.takeIf { it.isNotBlank() }?.let {
                    add("$it (Dub)")
                }
                addAll(baseTitles)
            } else {
                addAll(baseTitles)
            }
        }.distinct()

        val allCandidates = mutableListOf<MatchCandidate>()

        for (source in sources) {
            if (meta.type !in source.supportedTypes) continue

            val sourceCandidates = mutableListOf<MatchCandidate>()
            for (q in queries) {
                val results = runCatching { source.search(q, meta.type) }.getOrDefault(emptyList())
                for (media in results) {
                    score(meta, media)?.let { sourceCandidates.add(MatchCandidate(media, it.score, it.reasons, source)) }
                }
                if (sourceCandidates.any { it.score >= 0.55 }) {
                    break
                }
            }

            allCandidates.addAll(sourceCandidates)

            // Short-circuit: if a high-priority source yields a strong match (>= 0.85),
            // return immediately without waiting for remaining sources.
            val strongMatch = sourceCandidates.filter { it.score >= 0.85 }.maxByOrNull { it.score }
            if (strongMatch != null) {
                return MatchResult.Matched(source.id, source.name, strongMatch.media, strongMatch.score)
            }
        }

        val best = allCandidates.filter { it.score >= threshold }.maxByOrNull { it.score }
            ?: return MatchResult.NoMatch
        val source = best.source ?: return MatchResult.NoMatch
        return MatchResult.Matched(source.id, source.name, best.media, best.score)
    }

    /** Returns null when the candidate is the wrong type; otherwise a scored candidate. */
    fun score(meta: MetaQuery, media: SourceMedia): MatchCandidate? {
        if (media.type != meta.type) return null

        val variants = buildList {
            meta.titles.english?.let { add(it) }
            meta.titles.romaji?.let { add(it) }
            meta.titles.native?.let { add(it) }
            meta.synonyms.forEach { add(it) }
        }.filter { it.isNotBlank() }

        var bestSimilarity = 0.0
        variants.forEach { v -> bestSimilarity = maxOf(bestSimilarity, similarity(v, media.title)) }

        val reasons = mutableListOf<String>()
        var score = bestSimilarity * TITLE_WEIGHT
        reasons += "title ${fmt(bestSimilarity)}"

        val titleLower = media.title.lowercase()
        val isExplicitDub = titleLower.contains("(dub)") || titleLower.contains(" dub") || titleLower.contains("[dub]")
        if (meta.requestedAudio == com.animarvle.sourceapi.Audio.DUB && meta.type == com.animarvle.sourceapi.MediaType.ANIME) {
            if (isExplicitDub) {
                score += DUB_MATCH_BONUS
                reasons += "dub match +$DUB_MATCH_BONUS"
            }
        } else if (meta.requestedAudio == com.animarvle.sourceapi.Audio.SUB && meta.type == com.animarvle.sourceapi.MediaType.ANIME) {
            if (isExplicitDub) {
                score -= DUB_PENALTY_FOR_SUB
                reasons += "dub penalty for sub -$DUB_PENALTY_FOR_SUB"
            }
        }

        val metaYear = meta.year
        val candidateYear = media.year
        if (metaYear != null && candidateYear != null) {
            val diff = kotlin.math.abs(metaYear - candidateYear)
            if (diff == 0) {
                score += YEAR_EXACT
                reasons += "year exact"
            } else if (diff <= YEAR_TOLERANCE) {
                score += YEAR_NEAR * (1.0 - diff.toDouble() / (YEAR_TOLERANCE + 1))
                reasons += "year ±$diff"
            } else if (meta.type == MediaType.MOVIE) {
                // Movies: a wrong year means a different film (remakes), so drop it.
                score -= YEAR_PENALTY
                reasons += "year mismatch ($diff)"
            }
        }

        if (meta.type == MediaType.MOVIE) {
            // For movies the year dominates; ignore episode count entirely (ARCH §7.1).
            score = score * MOVIE_YEAR_BOOST
        } else {
            val metaEps = meta.episodeCount
            val candEps = media.episodeCount
            if (metaEps != null && candEps != null &&
                kotlin.math.abs(metaEps - candEps) <= EPISODE_TOLERANCE
            ) {
                score += EPISODE_MATCH
                reasons += "episodes ±$EPISODE_TOLERANCE"
            }
        }

        reasons += "type ${media.type}"
        return MatchCandidate(media, score, reasons)
    }

    /** Case/punctuation/season-suffix-insensitive similarity in 0..1 (ARCH §7.1). */
    fun similarity(a: String, b: String): Double {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        if (na == nb) return 1.0
        if (na.contains(nb) || nb.contains(na)) return 0.9

        val ta = na.split(' ').filter { it.isNotEmpty() }.toSet()
        val tb = nb.split(' ').filter { it.isNotEmpty() }.toSet()
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val intersection = ta.intersect(tb).size
        val jaccard = intersection.toDouble() / ta.union(tb).size

        // If one title is a substring of the other, weight by length ratio to penalize long spin-offs
        if (na.contains(nb) || nb.contains(na)) {
            val lengthRatio = minOf(na.length, nb.length).toDouble() / maxOf(na.length, nb.length).toDouble()
            return (jaccard * 0.4) + (lengthRatio * 0.6)
        }

        return jaccard
    }

    fun normalize(raw: String): String {
        var s = raw.lowercase()
        // strip season/arc noise: "season 2", "s2", "2nd season", "第2期"
        s = SEASON_SUFFIX.replace(s, " ")
        s = S_MARKER.replace(s, " season ")
        s = AUDIO_SUFFIX.replace(s, " ")
        s = NON_ALNUM.replace(s, " ")
        return s.split(' ').filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun fmt(d: Double) = String.format("%.2f", d)

    companion object {
        const val DEFAULT_THRESHOLD = 0.5
        private const val MAX_SYNONYMS = 3
        private const val TITLE_WEIGHT = 0.6
        private const val YEAR_EXACT = 0.35
        private const val YEAR_NEAR = 0.2
        private const val YEAR_PENALTY = 0.5
        private const val YEAR_TOLERANCE = 1
        private const val EPISODE_MATCH = 0.15
        private const val EPISODE_TOLERANCE = 1
        private const val MOVIE_YEAR_BOOST = 1.6
        private const val DUB_MATCH_BONUS = 0.35
        private const val DUB_PENALTY_FOR_SUB = 0.40

        private val AUDIO_SUFFIX = Regex("\\b(dub|sub|subbed|dubbed|raw)\\b")
        private val SEASON_SUFFIX = Regex(
            "\\b(season|staffel|saison)\\s*\\d{1,2}\\b|\\b\\d{1,2}(st|nd|rd|th)\\s+season\\b|第\\d{1,2}期",
        )
        private val S_MARKER = Regex("\\bs(\\d{1,2})\\b")
        private val NON_ALNUM = Regex("[^a-z0-9\\u3040-\\u30ff\\u4e00-\\u9fff]+")
    }
}