package com.animarvle.app.ui.search

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray

class RecentSearchRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _recentSearches = MutableStateFlow<List<String>>(emptyList())
    val recentSearches: StateFlow<List<String>> = _recentSearches.asStateFlow()

    init {
        load()
    }

    private fun load() {
        val raw = prefs.getString(KEY_QUERIES, null) ?: return
        runCatching {
            val arr = JSONArray(raw)
            val list = (0 until arr.length()).map { arr.getString(it) }
            _recentSearches.value = list
        }
    }

    suspend fun addSearchQuery(query: String) = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return@withContext
        val current = _recentSearches.value.toMutableList()
        current.removeAll { it.equals(trimmed, ignoreCase = true) }
        current.add(0, trimmed)
        val capped = current.take(MAX_RECENT_SEARCHES)
        save(capped)
    }

    suspend fun removeSearchQuery(query: String) = withContext(Dispatchers.IO) {
        val current = _recentSearches.value.toMutableList()
        if (current.removeAll { it.equals(query, ignoreCase = true) }) {
            save(current)
        }
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        save(emptyList())
    }

    private fun save(items: List<String>) {
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        prefs.edit().putString(KEY_QUERIES, arr.toString()).apply()
        _recentSearches.value = items
    }

    companion object {
        private const val PREFS_NAME = "animarvel_recent_searches"
        private const val KEY_QUERIES = "recent_queries"
        private const val MAX_RECENT_SEARCHES = 10
    }
}
