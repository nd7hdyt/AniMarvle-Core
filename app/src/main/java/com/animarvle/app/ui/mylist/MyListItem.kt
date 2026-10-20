package com.animarvle.app.ui.mylist

enum class MyListStatus(val key: String, val label: String) {
    WATCHING("watching", "Watching"),
    PLAN_TO_WATCH("plan_to_watch", "Plan to Watch"),
    COMPLETED("completed", "Completed"),
    ON_HOLD("on_hold", "On Hold"),
    DROPPED("dropped", "Dropped");

    companion object {
        fun fromKey(key: String): MyListStatus? = entries.firstOrNull { it.key == key }
    }
}

data class MyListItem(
    val mediaId: Long,
    val title: String,
    val posterUrl: String?,
    val format: String?,
    val score: Double?,
    val status: MyListStatus,
    val progress: Int = 0,
    val totalEpisodes: Int? = null,
    val updatedAtMs: Long = System.currentTimeMillis(),
)
