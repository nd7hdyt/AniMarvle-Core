package com.animarvle.app.ui.player

import androidx.media3.common.TrackGroup
import com.animarvle.sourceapi.Audio

enum class SpokenLanguage(
    val code: String,
    val iso3: String,
    val displayName: String,
    val aliases: List<String>
) {
    JAPANESE("ja", "jpn", "Japanese", listOf("ja", "jpn", "japanese", "jp")),
    ENGLISH("en", "eng", "English", listOf("en", "eng", "english", "en-us", "en-gb")),
    HINDI("hi", "hin", "Hindi", listOf("hi", "hin", "hindi")),
    TAMIL("ta", "tam", "Tamil", listOf("ta", "tam", "tamil")),
    TELUGU("te", "tel", "Telugu", listOf("te", "tel", "telugu")),
    URDU("ur", "urd", "Urdu", listOf("ur", "urd", "urdu")),
    BENGALI("bn", "ben", "Bengali", listOf("bn", "ben", "bengali")),
    MALAYALAM("ml", "mal", "Malayalam", listOf("ml", "mal", "malayalam")),
    KANNADA("kn", "kan", "Kannada", listOf("kn", "kan", "kannada")),
    KOREAN("ko", "kor", "Korean", listOf("ko", "kor", "korean", "kr", "k-drama")),
    CHINESE("zh", "zho", "Chinese", listOf("zh", "zho", "chi", "chinese", "mandarin", "donghua", "c-drama")),
    SPANISH("es", "spa", "Spanish", listOf("es", "spa", "spanish", "castilian", "latino", "es-")),
    FRENCH("fr", "fra", "French", listOf("fr", "fra", "fre", "french")),
    GERMAN("de", "deu", "German", listOf("de", "deu", "ger", "german")),
    ITALIAN("it", "ita", "Italian", listOf("it", "ita", "italian")),
    PORTUGUESE("pt", "por", "Portuguese", listOf("pt", "por", "portuguese", "br")),
    RUSSIAN("ru", "rus", "Russian", listOf("ru", "rus", "russian")),
    ARABIC("ar", "ara", "Arabic", listOf("ar", "ara", "arabic")),
    TAGALOG("tl", "tgl", "Tagalog", listOf("tl", "tgl", "tagalog", "filipino", "fil", "pinoy")),
    TURKISH("tr", "tur", "Turkish", listOf("tr", "tur", "turkish", "turkce", "türkçe"));

    companion object {
        fun fromRawTag(raw: String?): SpokenLanguage? {
            if (raw.isNullOrBlank()) return null
            val normalized = raw.trim().lowercase()

            // 1. Exact match on code, iso3, displayName, or alias
            entries.firstOrNull { lang ->
                lang.code == normalized ||
                lang.iso3 == normalized ||
                lang.displayName.lowercase() == normalized ||
                lang.aliases.any { it == normalized }
            }?.let { return it }

            // 2. Word-boundary match on explicit displayName first (prevents "sub" or "br" from hijacking "Hindi Sub" or "BR-Rip")
            entries.firstOrNull { lang ->
                val displayLower = Regex.escape(lang.displayName.lowercase())
                Regex("\\b$displayLower\\b").containsMatchIn(normalized)
            }?.let { return it }

            // 3. Word-boundary match on ISO codes and specific aliases (excluding ambiguous "sub"/"raw"/"original"/"br")
            val ambiguousAliases = setOf("sub", "raw", "original", "br")
            return entries.firstOrNull { lang ->
                lang.aliases.filter { it !in ambiguousAliases }.any { alias ->
                    val escaped = Regex.escape(alias.trimEnd('-'))
                    Regex("\\b$escaped\\b").containsMatchIn(normalized)
                }
            }
        }

        fun isUntaggedOrUndetermined(tag: String?): Boolean {
            if (tag.isNullOrBlank()) return true
            val n = tag.trim().lowercase()
            return n == "und" || n == "undetermined" || n == "unknown" || n == "default" || n == "null"
        }

        fun inferLanguageForUntaggedTrack(
            isMovie: Boolean,
            isDonghua: Boolean,
            isAnime: Boolean,
            audioPreference: Audio?,
            activeMediaLanguage: String? = null
        ): SpokenLanguage {
            val active = fromRawTag(activeMediaLanguage)
            if (active != null) return active

            if (audioPreference == Audio.DUB) {
                return ENGLISH
            }

            return when {
                isDonghua -> CHINESE
                isAnime -> JAPANESE
                else -> ENGLISH
            }
        }

        fun defaultMediaLanguage(
            isMovie: Boolean,
            isDonghua: Boolean,
            isAnime: Boolean
        ): SpokenLanguage {
            return when {
                isDonghua -> CHINESE
                isAnime -> JAPANESE
                else -> ENGLISH
            }
        }
    }
}

/**
 * Represents a selectable audio language in the Player UI and tracks HOW it is delivered.
 */
sealed class ResolvedAudioOption(
    open val languageName: String,
    open val languageCode: String
) {
    /**
     * Case 1: Embedded inside the active HLS / MKV / MP4 stream.
     * Switches instantaneously via ExoPlayer TrackSelectionOverride without reloading.
     */
    data class EmbeddedTrack(
        override val languageName: String,
        override val languageCode: String,
        val trackGroup: TrackGroup,
        val trackIndex: Int
    ) : ResolvedAudioOption(languageName, languageCode)

    /**
     * Case 2: Hosted on a separate server/stream URL.
     * Switches by loading the target server's stream and restoring playback position.
     */
    data class ServerStream(
        override val languageName: String,
        override val languageCode: String,
        val defaultServerKey: String,
        val serverUrls: List<ServerStreamItem> = emptyList(),
        val audioCategory: Audio? = null,
    ) : ResolvedAudioOption(languageName, languageCode)
}

data class ServerStreamItem(
    val serverKey: String,
    val serverName: String,
    val streamUrl: String = "",
    val languageCode: String,
    val languageName: String,
    val format: String = "hls",
    val audioCategory: Audio? = null,
)

val SpokenLanguage.flagEmoji: String
    get() = when (this) {
        SpokenLanguage.ENGLISH -> "🇺🇸"
        SpokenLanguage.JAPANESE -> "🇯🇵"
        SpokenLanguage.KOREAN -> "🇰🇷"
        SpokenLanguage.TURKISH -> "🇹🇷"
        SpokenLanguage.SPANISH -> "🇪🇸"
        SpokenLanguage.HINDI -> "🇮🇳"
        SpokenLanguage.GERMAN -> "🇩🇪"
        SpokenLanguage.FRENCH -> "🇫🇷"
        SpokenLanguage.RUSSIAN -> "🇷🇺"
        SpokenLanguage.PORTUGUESE -> "🇧🇷"
        SpokenLanguage.ARABIC -> "🇸🇦"
        SpokenLanguage.ITALIAN -> "🇮🇹"
        SpokenLanguage.CHINESE -> "🇨🇳"
        SpokenLanguage.TAGALOG -> "🇵🇭"
        SpokenLanguage.TAMIL, SpokenLanguage.TELUGU, SpokenLanguage.URDU,
        SpokenLanguage.BENGALI, SpokenLanguage.MALAYALAM, SpokenLanguage.KANNADA -> "🇮🇳"
    }

enum class BrandedServer(
    val serverKey: String,
    val displayName: String,
    val defaultFlag: String,
    val isMultiAudioCapable: Boolean = false,
    val supportsAnime: Boolean = true,
    val supportsMoviesTv: Boolean = true,
    val isAdultOnly: Boolean = false,
) {
    ONYX("onyx", "Onyx", "🇯🇵", supportsAnime = true, supportsMoviesTv = false),
    KITSUNE("kitsune", "Kitsune", "🇯🇵", supportsAnime = true, supportsMoviesTv = false),
    VELVET("velvet", "Velvet", "🇰🇷", supportsAnime = true, supportsMoviesTv = true),
    SPIRAL("spiral", "Spiral", "🇯🇵", supportsAnime = true, supportsMoviesTv = true),
    TURBO("turbo", "Turbo", "🇯🇵", supportsAnime = true, supportsMoviesTv = true),
    CAT3_DIRECT("cat3_direct", "Cat3 Direct HD", "🇯🇵", supportsAnime = false, supportsMoviesTv = true, isAdultOnly = true),
    VIDHIDE("vidhide", "VidHide / DooPlayer HD", "🇯🇵", supportsAnime = false, supportsMoviesTv = true, isAdultOnly = true),
    PINOY_VIVAMAX("pinoy_vivamax", "Pinoy / Vivamax", "🇵🇭", supportsAnime = false, supportsMoviesTv = true, isAdultOnly = true),
    HALO("halo", "Halo", "🇺🇸", supportsAnime = false, supportsMoviesTv = true),
    NEBULA("nebula", "Nebula", "🇺🇸", supportsAnime = false, supportsMoviesTv = true),
    PRISM("prism", "Prism (multi-audio)", "🌐", isMultiAudioCapable = true, supportsAnime = false, supportsMoviesTv = true),
    SOLARA("solara", "Solara", "🇺🇸", supportsAnime = false, supportsMoviesTv = true);

    companion object {
        fun fromRawServerName(raw: String, sourceId: Long? = null): BrandedServer {
            if (sourceId == 40005L) return PINOY_VIVAMAX
            if (sourceId == 40006L) {
                val lower = raw.lowercase().trim()
                return if (lower.contains("vidhide") || lower.contains("dooplayer") || lower.contains("mostplayer") || lower.contains("jav")) {
                    VIDHIDE
                } else {
                    CAT3_DIRECT
                }
            }
            val lower = raw.lowercase().trim()
            return when {
                lower.contains("onyx") || lower.contains("hd-1") || lower.contains("hd1") || lower.contains("megacloud") || lower.contains("megaplay") -> ONYX
                lower.contains("kitsune") || lower.contains("vidstream") || lower.contains("gogo") || lower.contains("anitaku") || lower.contains("vidwish") -> KITSUNE
                lower.contains("velvet") || lower.contains("kisskh") -> VELVET
                lower.contains("spiral") -> SPIRAL
                lower.contains("turbo") -> TURBO
                lower.contains("pinoy") || lower.contains("vivamax") || lower.contains("tagalog") || lower.contains("filipino") ||
                        lower.contains("playmogo") || lower.contains("mixdrop") || lower.contains("streamhls") || lower.contains("abstream") || lower.contains("voe") || lower.contains("shanelocal") -> PINOY_VIVAMAX
                lower.contains("cat3") -> CAT3_DIRECT
                lower.contains("vidhide") || lower.contains("dooplayer") || lower.contains("mostplayer") || lower.contains("jav") -> VIDHIDE
                lower.contains("asian") -> CAT3_DIRECT
                lower.contains("halo") || lower.contains("moviebox") || lower.contains("worker 1080") || lower.contains("worker 720") || lower.contains("worker 360") -> HALO
                lower.contains("nebula") || lower.contains("vidlink") -> NEBULA
                lower.contains("prism") || lower.contains("multiaudio") || lower.contains("multi-audio") || lower.contains("videasy") -> PRISM
                lower.contains("solara") || lower.contains("fastx") || lower.contains("fast") || lower.contains("2embed") || lower.contains("vidsrc") -> SOLARA
                else -> HALO
            }
        }
    }
}

enum class ServerHealthStatus {
    READY,      // Green dot (stream resolved & ready or currently playing)
    AVAILABLE,  // Muted green/grey dot (can be resolved on-demand)
    FAILED      // Red dot (failed / unavailable)
}

data class ServerModalItem(
    val serverKey: String,
    val displayName: String,
    val flagEmoji: String,
    val status: ServerHealthStatus,
    val isSelected: Boolean,
    val underlyingSourceKey: String? = null,
    val bundledQualities: Map<String, String> = emptyMap()
)
