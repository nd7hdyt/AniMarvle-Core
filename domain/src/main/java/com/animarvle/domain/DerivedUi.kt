package com.animarvle.domain

import com.animarvle.sourceapi.Audio
import com.animarvle.sourceapi.EpisodeOptions
import com.animarvle.sourceapi.ResolvedMedia
import com.animarvle.sourceapi.Stream
import com.animarvle.sourceapi.StreamKind
import com.animarvle.sourceapi.SubtitleTrack

/**
 * Capability -> UI behavior (ARCH §6). The host derives every control from what a plugin
 * RETURNS, never from SourceCapabilities (static hints only).
 */
object DerivedUi {

    /** How the Sub|Dub control must appear for the options a source returned. */
    data class AudioTabs(
        val subEnabled: Boolean,
        val dubEnabled: Boolean,
        /** True when the control is omitted entirely (RAW-only, typical for movies). */
        val hidden: Boolean,
        val unavailableHint: String? = null,
    )

    /**
     * ARCH §6: SUB+DUB -> both enabled; one audio -> the other is DISABLED with a hint;
     * RAW only -> the whole control is HIDDEN, never disabled.
     */
    fun audioTabs(options: EpisodeOptions): AudioTabs {
        val hasSub = options.byAudio.containsKey(Audio.SUB)
        val hasDub = options.byAudio.containsKey(Audio.DUB)
        val hasRaw = options.byAudio.containsKey(Audio.RAW)

        if (!hasSub && !hasDub && hasRaw) {
            return AudioTabs(subEnabled = false, dubEnabled = false, hidden = true)
        }
        return AudioTabs(
            subEnabled = hasSub,
            dubEnabled = hasDub,
            hidden = false,
            unavailableHint = if (!hasSub && hasDub) "Not available from this source"
            else if (!hasDub && hasSub) "Not available from this source"
            else null,
        )
    }

    data class QualityItem(
        val id: String,
        val label: String,
        val height: Int? = null,
        /** null = adaptive; non-null = a concrete stream to prepare. */
        val stream: Stream? = null,
    )

    fun sanitizeQualityLabel(raw: String?): String {
        if (raw.isNullOrBlank()) return "Auto"
        val lower = raw.lowercase()
        return when {
            lower.contains("2160") || lower.contains("4k") -> "4K"
            lower.contains("1080") -> "1080p"
            lower.contains("720") -> "720p"
            lower.contains("480") -> "480p"
            lower.contains("360") -> "360p"
            lower.contains("auto") -> "Auto"
            else -> raw.trim()
        }
    }

    /**
     * ARCH §6:
     *  - one HLS/DASH master -> "Auto" + whatever the player exposes (adaptive);
     *  - several variants with height/label -> those, sorted high->low, plus "Auto";
     *  - a single stream with no quality info -> no sheet at all (null).
     */
    fun qualitySheet(media: ResolvedMedia, adaptiveLabels: List<Stream> = emptyList()): List<QualityItem>? {
        val adaptive = adaptiveLabels.filter { it.height != null || it.qualityLabel != null }
        if (adaptive.isNotEmpty()) {
            return buildList {
                add(QualityItem(id = AUTO_ID, label = "Auto"))
                addAll(
                    adaptive
                        .sortedByDescending { it.height ?: 0 }
                        .map { QualityItem(id = idOf(it), label = labelOf(it), height = it.height, stream = it) }
                        .distinctBy { it.label },
                )
            }
        }

        val variants = media.streams.filter { it.height != null || it.qualityLabel != null }
        if (variants.size > 1) {
            return buildList {
                add(QualityItem(id = AUTO_ID, label = "Auto"))
                addAll(
                    variants
                        .sortedByDescending { it.height ?: 0 }
                        .map { QualityItem(id = idOf(it), label = labelOf(it), height = it.height, stream = it) }
                        .distinctBy { it.label },
                )
            }
        }

        if (variants.size == 1) {
            val v = variants.first()
            return listOf(
                QualityItem(id = AUTO_ID, label = "Auto"),
                QualityItem(id = idOf(v), label = labelOf(v), height = v.height, stream = v),
            )
        }

        return null
    }

    fun labelOf(s: Stream): String {
        val raw = s.qualityLabel ?: s.height?.let { "${it}p" } ?: s.url
        return sanitizeQualityLabel(raw)
    }

    fun idOf(s: Stream): String = "${labelOf(s)}#${s.url}"

    /** ARCH §6: honor the default-language setting; exact match first, then base language. */
    fun pickSubtitle(tracks: List<SubtitleTrack>, defaultLanguage: String): SubtitleTrack? =
        tracks.firstOrNull { it.language.equals(defaultLanguage, ignoreCase = true) }
            ?: tracks.firstOrNull {
                it.language.substringBefore('-').equals(
                    defaultLanguage.substringBefore('-'),
                    ignoreCase = true,
                )
            }

    /** ARCH §8: progress >= 90 % marks the episode watched. */
    fun isWatched(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0L) return false
        return positionMs.toDouble() / durationMs.toDouble() >= WATCHED_THRESHOLD
    }

    const val AUTO_ID = "auto"
    const val WATCHED_THRESHOLD = 0.9

    /** A movie is one playable unit; ARCH §10 test 10/11 rely on this. */
    fun isMovie(type: com.animarvle.sourceapi.MediaType) = type == com.animarvle.sourceapi.MediaType.MOVIE

    fun isAdaptive(s: Stream) = s.kind == StreamKind.HLS || s.kind == StreamKind.DASH
}