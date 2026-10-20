package com.animarvle.app.data.binding

import android.content.Context
import android.content.SharedPreferences

class AnimeSourceBindingRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("anime_source_bindings", Context.MODE_PRIVATE)

    fun getBoundSourceId(animeId: Long): Long? {
        val v = prefs.getLong(keySource(animeId), -1L)
        return if (v != -1L) v else null
    }

    fun setBoundSourceId(animeId: Long, sourceId: Long) {
        prefs.edit().putLong(keySource(animeId), sourceId).apply()
    }

    fun getCustomQuery(animeId: Long): String? {
        return prefs.getString(keyQuery(animeId), null)
    }

    fun setCustomQuery(animeId: Long, query: String?) {
        if (query.isNullOrBlank()) {
            prefs.edit().remove(keyQuery(animeId)).apply()
        } else {
            prefs.edit().putString(keyQuery(animeId), query.trim()).apply()
        }
    }

    fun clearBinding(animeId: Long) {
        prefs.edit()
            .remove(keySource(animeId))
            .remove(keyQuery(animeId))
            .apply()
    }

    private fun keySource(animeId: Long) = "source_$animeId"
    private fun keyQuery(animeId: Long) = "query_$animeId"
}
