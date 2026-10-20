package com.animarvle.app.ui.mylist

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MyListRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _itemsFlow = MutableStateFlow<List<MyListItem>>(emptyList())
    val itemsFlow: StateFlow<List<MyListItem>> = _itemsFlow.asStateFlow()

    init {
        loadFromPrefs()
    }

    private fun loadFromPrefs() {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return
        runCatching {
            val arr = JSONArray(raw)
            val list = (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val statusStr = o.optString("status")
                val status = MyListStatus.fromKey(statusStr) ?: return@mapNotNull null
                MyListItem(
                    mediaId = o.getLong("mediaId"),
                    title = o.getString("title"),
                    posterUrl = o.optString("posterUrl", null),
                    format = o.optString("format", null),
                    score = if (o.has("score")) o.optDouble("score") else null,
                    status = status,
                    progress = o.optInt("progress", 0),
                    totalEpisodes = if (o.has("totalEpisodes")) o.optInt("totalEpisodes") else null,
                    updatedAtMs = o.optLong("updatedAtMs", System.currentTimeMillis()),
                )
            }
            _itemsFlow.value = list
        }
    }

    suspend fun getStatus(mediaId: Long): MyListStatus? = withContext(Dispatchers.IO) {
        _itemsFlow.value.firstOrNull { it.mediaId == mediaId }?.status
    }

    suspend fun upsert(item: MyListItem) = withContext(Dispatchers.IO) {
        val current = _itemsFlow.value.toMutableList()
        current.removeAll { it.mediaId == item.mediaId }
        current.add(0, item)
        save(current)
    }

    suspend fun remove(mediaId: Long) = withContext(Dispatchers.IO) {
        val current = _itemsFlow.value.toMutableList()
        val removed = current.removeAll { it.mediaId == mediaId }
        if (removed) {
            save(current)
        }
    }

    private fun save(items: List<MyListItem>) {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(JSONObject().apply {
                put("mediaId", item.mediaId)
                put("title", item.title)
                put("posterUrl", item.posterUrl ?: JSONObject.NULL)
                put("format", item.format ?: JSONObject.NULL)
                if (item.score != null) put("score", item.score)
                put("status", item.status.key)
                put("progress", item.progress)
                if (item.totalEpisodes != null) put("totalEpisodes", item.totalEpisodes)
                put("updatedAtMs", item.updatedAtMs)
            })
        }
        prefs.edit().putString(KEY_ITEMS, arr.toString()).apply()
        _itemsFlow.value = items
    }

    companion object {
        private const val PREFS_NAME = "animarvel_my_list"
        private const val KEY_ITEMS = "items"
    }
}
