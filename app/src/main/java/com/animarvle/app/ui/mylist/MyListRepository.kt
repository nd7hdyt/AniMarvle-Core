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

    var onListChangedListener: (() -> Unit)? = null

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
                    year = if (o.has("year") && !o.isNull("year")) o.optInt("year") else null,
                    mediaType = if (o.has("mediaType") && !o.isNull("mediaType")) o.optString("mediaType") else null,
                )
            }
            _itemsFlow.value = list
        }
    }

    suspend fun getStatus(mediaId: Long, mediaType: String? = null): MyListStatus? = withContext(Dispatchers.IO) {
        _itemsFlow.value.firstOrNull {
            it.mediaId == mediaId && (mediaType == null || it.mediaType == null || it.mediaType.equals(mediaType, ignoreCase = true))
        }?.status
    }

    suspend fun upsert(item: MyListItem) = withContext(Dispatchers.IO) {
        val current = _itemsFlow.value.toMutableList()
        current.removeAll {
            it.mediaId == item.mediaId && (it.mediaType == null || item.mediaType == null || it.mediaType.equals(item.mediaType, ignoreCase = true))
        }
        current.add(0, item)
        clearTombstone(item)
        save(current)
    }

    suspend fun remove(mediaId: Long, mediaType: String? = null) = withContext(Dispatchers.IO) {
        val current = _itemsFlow.value.toMutableList()
        val toRemove = current.filter {
            it.mediaId == mediaId && (mediaType == null || it.mediaType == null || it.mediaType.equals(mediaType, ignoreCase = true))
        }
        if (toRemove.isNotEmpty()) {
            current.removeAll(toRemove)
            toRemove.forEach { item ->
                recordTombstone(item.uniqueKey, System.currentTimeMillis())
                recordTombstone(item.mediaId.toString(), System.currentTimeMillis())
            }
            save(current)
        }
    }

    suspend fun saveAll(items: List<MyListItem>, notifyListener: Boolean = true) = withContext(Dispatchers.IO) {
        save(items, notifyListener)
    }

    fun serialize(items: List<MyListItem> = _itemsFlow.value): String {
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
                if (item.year != null && item.year > 0) put("year", item.year)
                if (!item.mediaType.isNullOrBlank()) put("mediaType", item.mediaType)
            })
        }
        return arr.toString()
    }

    fun deserialize(raw: String): List<MyListItem> {
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
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
                    year = if (o.has("year") && !o.isNull("year")) o.optInt("year") else null,
                    mediaType = if (o.has("mediaType") && !o.isNull("mediaType")) o.optString("mediaType") else null,
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun save(items: List<MyListItem>, notifyListener: Boolean = true) {
        val serialized = serialize(items)
        prefs.edit().putString(KEY_ITEMS, serialized).apply()
        _itemsFlow.value = items
        if (notifyListener) {
            onListChangedListener?.invoke()
        }
    }

    private fun recordTombstone(key: String, timestampMs: Long) {
        val raw = prefs.getString(KEY_TOMBSTONES, null)
        val json = if (raw != null) runCatching { JSONObject(raw) }.getOrDefault(JSONObject()) else JSONObject()
        json.put(key, timestampMs)
        // Evict tombstones older than 60 days to prevent unbounded growth
        val cutoff = System.currentTimeMillis() - 60L * 24 * 60 * 60 * 1000
        val keysToRemove = mutableListOf<String>()
        val it = json.keys()
        while (it.hasNext()) {
            val k = it.next()
            if (json.optLong(k, 0L) < cutoff) {
                keysToRemove.add(k)
            }
        }
        keysToRemove.forEach { json.remove(it) }
        prefs.edit().putString(KEY_TOMBSTONES, json.toString()).apply()
    }

    private fun clearTombstone(item: MyListItem) {
        val raw = prefs.getString(KEY_TOMBSTONES, null) ?: return
        runCatching {
            val json = JSONObject(raw)
            var modified = false
            if (json.has(item.uniqueKey)) {
                json.remove(item.uniqueKey)
                modified = true
            }
            if (json.has(item.mediaId.toString())) {
                json.remove(item.mediaId.toString())
                modified = true
            }
            if (modified) {
                prefs.edit().putString(KEY_TOMBSTONES, json.toString()).apply()
            }
        }
    }

    fun getTombstones(): Map<String, Long> {
        val raw = prefs.getString(KEY_TOMBSTONES, null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            val map = mutableMapOf<String, Long>()
            val it = json.keys()
            while (it.hasNext()) {
                val k = it.next()
                map[k] = json.getLong(k)
            }
            map
        }.getOrDefault(emptyMap())
    }

    companion object {
        private const val PREFS_NAME = "animarvel_my_list"
        private const val KEY_ITEMS = "items"
        private const val KEY_TOMBSTONES = "tombstones"
    }
}
