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
            genres = if (m.isAdult == true && !m.genres.orEmpty().any { it.equals("Hentai", ignoreCase = true) }) {
                m.genres.orEmpty() + "Hentai"
            } else {
                m.genres.orEmpty()
            },
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
        val nodesMap = mutableMapOf<Long, Media>()
        nodesMap[m.id] = m

        val prequelOf = mutableMapOf<Long, Long>() // childId -> parentId
        val sequelOf = mutableMapOf<Long, Long>()  // parentId -> childId

        fun traverse(node: Media) {
            val nid = node.id
            node.relations?.edges.orEmpty().forEach { edge ->
                val sub = edge.node ?: return@forEach
                val rel = edge.relationType
                val format = sub.format
                if (format == "MANGA" || format == "NOVEL" || format == "ONE_SHOT" || format == "MUSIC") return@forEach
                val isOneShot = (sub.episodes ?: 0) <= 1 && (format == "ONA" || format == "OVA" || format == "SPECIAL")
                if (isOneShot) return@forEach

                val sid = sub.id
                if (!nodesMap.containsKey(sid)) {
                    nodesMap[sid] = sub
                    traverse(sub)
                }

                if (rel == "PREQUEL") {
                    prequelOf[nid] = sid
                    if (!sequelOf.containsKey(sid)) sequelOf[sid] = nid
                } else if (rel == "SEQUEL") {
                    sequelOf[nid] = sid
                    if (!prequelOf.containsKey(sid)) prequelOf[sid] = nid
                }
            }
        }

        traverse(m)

        // Trace backward to root of series
        var currId = m.id
        val visitedBack = mutableSetOf<Long>()
        while (prequelOf.containsKey(currId) && visitedBack.add(currId)) {
            currId = prequelOf[currId] ?: break
        }
        val firstId = currId

        // Trace forward to build chronological season chain
        val chain = mutableListOf<Media>()
        var forwardId: Long? = firstId
        val visitedForward = mutableSetOf<Long>()
        while (forwardId != null && visitedForward.add(forwardId)) {
            val node = nodesMap[forwardId]
            if (node != null) {
                chain.add(node)
            }
            forwardId = sequelOf[forwardId]
        }

        if (chain.none { it.id == m.id }) {
            chain.add(0, m)
        }

        // If any item has TV/ONA format, filter out non-main formats unless it's current media
        val hasTv = chain.any { it.format == "TV" || it.format == "ONA" || it.format == "TV_SHORT" }
        val finalChain = if (hasTv) {
            chain.filter { it.format == "TV" || it.format == "ONA" || it.format == "TV_SHORT" || it.format == "MOVIE" || it.id == m.id }
        } else {
            chain
        }.distinctBy { it.id }

        if (finalChain.size <= 1) {
            val currentTitle = m.title?.let { t ->
                t.english?.takeIf { it.isNotBlank() } ?: t.romaji?.takeIf { it.isNotBlank() } ?: t.native
            } ?: "Season 1"
            val currentCover = m.coverImage?.extraLarge ?: m.coverImage?.large
            return listOf(
                com.animarvle.app.domain.SeasonUi(
                    id = m.id,
                    title = currentTitle,
                    posterUrl = currentCover,
                    isCurrent = true,
                    seasonNumber = 1,
                    episodeCount = m.episodes,
                )
            )
        }

        return finalChain.mapIndexed { index, node ->
            val nodeTitle = node.title?.let { t ->
                t.english?.takeIf { it.isNotBlank() } ?: t.romaji?.takeIf { it.isNotBlank() } ?: t.native
            } ?: "Season ${index + 1}"
            val nodeCover = node.coverImage?.extraLarge ?: node.coverImage?.large
            com.animarvle.app.domain.SeasonUi(
                id = node.id,
                title = nodeTitle,
                posterUrl = nodeCover,
                isCurrent = (node.id == m.id),
                seasonNumber = index + 1,
                episodeCount = node.episodes,
            )
        }
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