package com.animarvle.app.ui.theme

import android.content.Context
import com.animarvle.app.R
import com.google.android.material.color.MaterialColors

/**
 * Runtime palette choice (PROMPT §5: two themes sharing one token set; dark only in v1).
 * The choice is a local preference; night mode does not follow the system in v1.
 */
object AppTheme {

    private const val PREFS = "animarvle_theme"
    private const val KEY = "palette"

    const val PALETTE_RED = "red"
    const val PALETTE_GREEN = "green"
    const val PALETTE_CYAN = "cyan"

    fun palette(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, PALETTE_CYAN) ?: PALETTE_CYAN

    /** Style overlay id for the stored palette, applied with setTheme before onCreate. */
    fun overlayFor(context: Context): Int = when (palette(context)) {
        PALETTE_RED -> R.style.Palette_Red
        PALETTE_GREEN -> R.style.Palette_Green
        else -> R.style.Palette_Cyan
    }

    fun nameFor(palette: String): String = when (palette) {
        PALETTE_RED -> "AniLab Red"
        PALETTE_GREEN -> "Th3-Anime Green"
        else -> "Zangetsu Cyan"
    }

    fun colorFor(palette: String): Int = when (palette) {
        PALETTE_RED -> 0xFFD8202C.toInt()
        PALETTE_GREEN -> 0xFF05C149.toInt()
        else -> 0xFF00D4B2.toInt()
    }

    /** Resolves the accent actually in effect, so tints follow the chosen palette. */
    fun accent(context: Context): Int =
        MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, 0)

    fun muted(context: Context): Int =
        MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            0,
        )

    fun setPalette(context: Context, palette: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, palette)
            .apply()
    }
}