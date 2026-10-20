package com.animarvle.app.extensions

/**
 * Universal representation of an extension item across engines (Aniyomi, Mangayomi).
 */
data class AvailableExtension(
    val id: String,
    val name: String,
    val pkg: String,
    val versionName: String,
    val versionCode: Long = 0,
    val lang: String = "all",
    val isNsfw: Boolean = false,
    val iconUrl: String? = null,
    val apkUrl: String? = null,
    val sourceCodeUrl: String? = null,
    val engine: ExtensionEngine = ExtensionEngine.ANIYOMI,
    val isMovieOrAnime: Boolean = true,
    val repoBaseUrl: String = "",
)

enum class ExtensionEngine(val label: String) {
    ALL("All"),
    MANGAYOMI("Mangayomi"),
    ANIYOMI("Aniyomi"),
    CLOUDSTREAM("Cloudstream"),
    SORA("Sora"),
}

enum class MediaCategory(val label: String) {
    ANIME("Anime"),
    MOVIES("Movie"),
}

