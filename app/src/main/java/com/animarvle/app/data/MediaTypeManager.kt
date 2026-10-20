package com.animarvle.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppMediaMode {
    ANIME,
    MOVIES_TV;

    fun isAnime() = this == ANIME
    fun isMoviesTv() = this == MOVIES_TV
}

class MediaTypeManager(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _currentMode = MutableStateFlow(readStoredMode())
    val currentMode: StateFlow<AppMediaMode> = _currentMode.asStateFlow()

    private fun readStoredMode(): AppMediaMode {
        val stored = prefs.getString(KEY_MODE, AppMediaMode.ANIME.name)
        return try {
            AppMediaMode.valueOf(stored ?: AppMediaMode.ANIME.name)
        } catch (_: Exception) {
            AppMediaMode.ANIME
        }
    }

    fun setMode(mode: AppMediaMode) {
        if (_currentMode.value != mode) {
            prefs.edit().putString(KEY_MODE, mode.name).apply()
            _currentMode.value = mode
        }
    }

    fun toggleMode(): AppMediaMode {
        val next = if (_currentMode.value == AppMediaMode.ANIME) AppMediaMode.MOVIES_TV else AppMediaMode.ANIME
        setMode(next)
        return next
    }

    companion object {
        private const val PREFS_NAME = "animarvel_media_mode_prefs"
        private const val KEY_MODE = "active_media_mode"

        @Volatile
        private var instance: MediaTypeManager? = null

        fun getInstance(context: Context): MediaTypeManager {
            return instance ?: synchronized(this) {
                instance ?: MediaTypeManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
