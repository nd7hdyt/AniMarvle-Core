package com.animarvle.app.data.anilist

import com.animarvle.app.domain.AiringStatus
import com.animarvle.app.domain.MediaUi
import com.animarvle.app.domain.ScheduleItem

/**
 * Maps AniList transport models to UI models.
 * Verified against the live API on 2026-10-11:
 *  - averageScore is 0..100 (Death Note = 84), so it is divided by 10 for display.
 *  - description still contains HTML tags even with asHtml:false, so tags are stripped.
 */
object AniListMapper {

    private val TAG = Regex("<[^>]*>")
    private val MULTI_SPACE = Regex("[ \\t]{2,}")

    fun toMediaUi(m: Media): MediaUi {
        val title = m.title?.let { t ->
            t.english?.takeIf { it.isNotBlank() }
                ?: t.romaji?.takeIf { it.isNotBlank() }
                ?: t.native
        } ?: "Untitled"

        if (m.countryOfOrigin.equals("CN", ignoreCase = true)) {
            com.animarvle.app.AnimarvleApp.registerDonghua(m.id, title, m.title?.romaji, m.title?.native)
        }

        return MediaUi(
            id = m.id,
            title = title,
            romajiTitle = m.title?.romaji?.takeIf { it.isNotBlank() },
            nativeTitle = m.title?.native?.takeIf { it.isNotBlank() && it != title },
            synonyms = m.synonyms.orEmpty().filter { it.isNotBlank() },
            synopsis = m.description?.let { clean(it) },
            coverUrl = m.coverImage?.extraLarge ?: m.coverImage?.large,
            bannerUrl = m.bannerImage,
            genres = m.genres.orEmpty(),
            score = m.averageScore?.let { it / 10.0 },
            year = m.seasonYear ?: m.startDate?.year,
            startDateIso = m.startDate?.let { iso(it) },
            format = m.format,
            episodes = m.episodes,
            durationMinutes = m.duration,
            status = status(m.status),
            nextAiringEpisode = m.nextAiringEpisode?.episode,
            nextAiringAtEpochSec = m.nextAiringEpisode?.airingAt,
            seasons = mapRelationsToSeasons(m),
        )
    }

    private fun mapRelationsToSeasons(m: Media): List<com.animarvle.app.domain.SeasonUi> {
        val edges = m.relations?.edges.orEmpty().filter { edge ->
            val node = edge.node
            val type = edge.relationType
            node != null && (type == "PREQUEL" || type == "SEQUEL" || type == "PARENT" || type == "SIDE_STORY" || type == "ALTERNATIVE" || type == "SPIN_OFF")
        }
        if (edges.isEmpty()) return emptyList()

        val currentTitle = m.title?.let { t ->
            t.english?.takeIf { it.isNotBlank() } ?: t.romaji?.takeIf { it.isNotBlank() } ?: t.native
        } ?: "Current"
        val currentCover = m.coverImage?.extraLarge ?: m.coverImage?.large

        val list = mutableListOf<com.animarvle.app.domain.SeasonUi>()
        edges.forEach { edge ->
            val node = edge.node ?: return@forEach
            val nodeTitle = node.title?.let { t ->
                t.english?.takeIf { it.isNotBlank() } ?: t.romaji?.takeIf { it.isNotBlank() } ?: t.native
            } ?: ""
            val nodeCover = node.coverImage?.extraLarge ?: node.coverImage?.large
            list.add(
                com.animarvle.app.domain.SeasonUi(
                    id = node.id,
                    title = nodeTitle,
                    posterUrl = nodeCover,
                    isCurrent = false,
                )
            )
        }
        list.add(
            com.animarvle.app.domain.SeasonUi(
                id = m.id,
                title = currentTitle,
                posterUrl = currentCover,
                isCurrent = true,
            )
        )
        return list.sortedBy { it.id }
    }

    fun toScheduleItem(n: AiringNode): ScheduleItem? {
        val media = n.media ?: return null
        val title = media.title?.let { t ->
            t.english?.takeIf { it.isNotBlank() }
                ?: t.romaji?.takeIf { it.isNotBlank() }
                ?: t.native
        } ?: return null
        return ScheduleItem(
            mediaId = media.id,
            title = title,
            coverUrl = media.coverImage?.large ?: media.coverImage?.extraLarge,
            episode = n.episode,
            airingAtEpochSec = n.airingAt,
            format = media.format,
            totalEpisodes = media.episodes,
        )
    }

    /** Search rows show the release/air date as YYYY-MM-DD (PROMPT §7.3). */
    private fun iso(d: FuzzyDate): String? {
        val y = d.year ?: return null
        val m = d.month ?: 1
        val day = d.day ?: 1
        return "%04d-%02d-%02d".format(y, m, day)
    }

    private fun status(raw: String?): AiringStatus = when (raw) {
        "FINISHED" -> AiringStatus.FINISHED
        "RELEASING" -> AiringStatus.RELEASING
        "NOT_YET_RELEASED" -> AiringStatus.NOT_YET_RELEASED
        "CANCELLED" -> AiringStatus.CANCELLED
        "HIATUS" -> AiringStatus.HIATUS
        else -> AiringStatus.UNKNOWN
    }

    /** Descriptions arrive with <br>, <i> etc. even when asHtml is false. */
    fun clean(html: String): String = html
        .replace("<br>", "\n", ignoreCase = true)
        .replace("<br/>", "\n", ignoreCase = true)
        .replace("<br />", "\n", ignoreCase = true)
        .replace(TAG, "")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&amp;", "&")
        .replace("&mdash;", "—")
        .replace(MULTI_SPACE, " ")
        .trim()
}