package com.animarvle.app.ui.player

object LanguageFlagHelper {
    /**
     * Resolves a country flag emoji for a given ISO language code or display name.
     */
    fun getFlag(langCode: String?, displayName: String? = null): String {
        val code = langCode?.trim()?.lowercase()?.substringBefore('-') ?: ""
        val name = displayName?.trim()?.lowercase() ?: ""

        return when {
            code == "en" || name.contains("english") -> "🇺🇸"
            code == "ar" || name.contains("arabic") -> "🇸🇦"
            code == "es" || name.contains("spanish") -> "🇪🇸"
            code == "pb" || name.contains("brazilian") || name.contains("portuguese (br)") -> "🇧🇷"
            code == "pt" || name.contains("portuguese") -> "🇵🇹"
            code == "fr" || name.contains("french") -> "🇫🇷"
            code == "de" || name.contains("german") -> "🇩🇪"
            code == "it" || name.contains("italian") -> "🇮🇹"
            code == "ru" || name.contains("russian") -> "🇷🇺"
            code == "ja" || name.contains("japanese") -> "🇯🇵"
            code == "ko" || name.contains("korean") -> "🇰🇷"
            code == "zh" || name.contains("chinese") -> "🇨🇳"
            code == "hi" || name.contains("hindi") -> "🇮🇳"
            code == "bg" || name.contains("bulgarian") -> "🇧🇬"
            code == "el" || name.contains("greek") -> "🇬🇷"
            code == "cs" || name.contains("czech") -> "🇨🇿"
            code == "sl" || name.contains("slovenian") -> "🇸🇮"
            code == "ro" || name.contains("romanian") -> "🇷🇴"
            code == "sv" || name.contains("swedish") -> "🇸🇪"
            code == "da" || name.contains("danish") -> "🇩🇰"
            code == "fi" || name.contains("finnish") -> "🇫🇮"
            code == "no" || name.contains("norwegian") -> "🇳🇴"
            code == "sr" || name.contains("serbian") -> "🇷🇸"
            code == "is" || name.contains("icelandic") -> "🇮🇸"
            code == "et" || name.contains("estonian") -> "🇪🇪"
            code == "he" || name.contains("hebrew") -> "🇮🇱"
            code == "hr" || name.contains("croatian") -> "🇭🇷"
            code == "tr" || name.contains("turkish") -> "🇹🇷"
            code == "pl" || name.contains("polish") -> "🇵🇱"
            code == "nl" || name.contains("dutch") -> "🇳🇱"
            code == "hu" || name.contains("hungarian") -> "🇭🇺"
            code == "uk" || name.contains("ukrainian") -> "🇺🇦"
            code == "id" || name.contains("indonesian") -> "🇮🇩"
            code == "vi" || name.contains("vietnamese") -> "🇻🇳"
            code == "th" || name.contains("thai") -> "🇹🇭"
            code == "fa" || name.contains("persian") -> "🇮🇷"
            code == "ur" || name.contains("urdu") -> "🇵🇰"
            code == "ta" || name.contains("tamil") -> "🇮🇳"
            code == "te" || name.contains("telugu") -> "🇮🇳"
            code == "ml" || name.contains("malayalam") -> "🇮🇳"
            code == "bn" || name.contains("bengali") -> "🇧🇩"
            code == "tl" || name.contains("tagalog") || name.contains("filipino") -> "🇵🇭"
            code == "ms" || name.contains("malay") -> "🇲🇾"
            else -> "🌐"
        }
    }
}

