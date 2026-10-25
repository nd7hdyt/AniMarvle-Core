package com.animarvle.app.ui.mylist

import android.content.Context
import android.content.SharedPreferences

enum class DisplayMode {
    COMPACT_GRID,
    COMFORTABLE_GRID,
    COVER_ONLY_GRID,
    LIST,
}

class MyListDisplayPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("my_list_display_prefs", Context.MODE_PRIVATE)

    var displayMode: DisplayMode
        get() {
            val name = prefs.getString("display_mode", DisplayMode.COMFORTABLE_GRID.name)
            return runCatching { DisplayMode.valueOf(name!!) }.getOrDefault(DisplayMode.COMFORTABLE_GRID)
        }
        set(value) = prefs.edit().putString("display_mode", value.name).apply()

    var itemsPerRow: Int
        get() = prefs.getInt("items_per_row", 3).coerceIn(1, 5)
        set(value) = prefs.edit().putInt("items_per_row", value.coerceIn(1, 5)).apply()
}
