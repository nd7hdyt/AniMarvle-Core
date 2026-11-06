package com.animarvle.app.ui.player

import androidx.media3.common.TrackGroup
import com.animarvle.sourceapi.Audio

enum class SpokenLanguage(
    val code: String,
    val iso3: String,
    val displayName: String,
    val aliases: List<String>
) {
    JAPANESE("ja", "jpn", "Japanese", listOf("ja", "jpn", "japanese", "jp", "sub", "original", "raw")),
    ENGLISH("en", "eng", "English", listOf("en", "eng", "english", "dub", "en-us", "en-gb")),
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
    ARABIC("ar", "ara", "Arabic", listOf("ar", "ara", "arabic"));

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
            if (audioPreference == Audio.DUB) {
                val active = fromRawTag(activeMediaLanguage)
                if (active != null && active != JAPANESE && active != CHINESE) return active
                return ENGLISH
            }
            if (audioPreference == Audio.SUB) {
                val active = fromRawTag(activeMediaLanguage)
                if (active != null && active != ENGLISH) return active
                return if (isDonghua) CHINESE else JAPANESE
            }
            val active = fromRawTag(activeMediaLanguage)
            if (active != null) return active

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
