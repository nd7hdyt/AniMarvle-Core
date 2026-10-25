package com.animarvle.app.ui.theme

import android.content.Context
import android.graphics.Color
import com.animarvle.app.R

object AppTheme {
    const val PALETTE_RED = "red"
    const val PALETTE_GREEN = "green"
    const val PALETTE_CYAN = "cyan"

    fun palette(context: Context): String = PALETTE_CYAN

    fun overlayFor(context: Context): Int = R.style.Theme_Animarvle

    fun nameFor(palette: String): String = "AniVortex OLED Black"

    fun colorFor(palette: String): Int = Color.WHITE

    fun accent(context: Context): Int = Color.WHITE

    fun muted(context: Context): Int = Color.parseColor("#9E9E9E")

    fun setPalette(context: Context, palette: String) {
        // Locked to AniVortex OLED Black theme
    }
}