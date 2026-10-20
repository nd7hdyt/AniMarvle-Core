package com.animarvle.app.ui.search

data class SearchFilterOptions(
    val category: String = "All",
    val type: String = "All",
    val status: String = "All",
    val season: String = "All",
    val sort: String = "Default",
) {
    val isDefault: Boolean
        get() = category == "All" && type == "All" && status == "All" && season == "All" && sort == "Default"
}
