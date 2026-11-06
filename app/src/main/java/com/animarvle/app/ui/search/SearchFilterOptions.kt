package com.animarvle.app.ui.search

import java.io.Serializable

data class SearchFilterOptions(
    val category: String = "All",
    val type: String = "All",
    val status: String = "Any",
    val season: String = "Any",
    val sort: String = "Trending",
    val format: String = "Any",
    val episodes: String = "Any",
    val minRating: Double = 0.0,
    val dubbedOnly: Boolean = false,
    val watchedOnly: String = "Show everything",
    val year: String = "Any",
    val genres: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val director: String? = null,
    val maleActor: String? = null,
    val femaleActress: String? = null,
    val studio: String? = null,
    val romanceType: String = "Any",
    val seriesType: String = "Any",
    val adultContent: String = "Any",
) : Serializable {

    val isDefault: Boolean
        get() = (category == "All" || category.isBlank()) &&
            (type == "All" || type.isBlank()) &&
            status == "Any" &&
            season == "Any" &&
            (sort == "Trending" || sort == "Default") &&
            format == "Any" &&
            episodes == "Any" &&
            minRating == 0.0 &&
            !dubbedOnly &&
            watchedOnly == "Show everything" &&
            year == "Any" &&
            genres.isEmpty() &&
            tags.isEmpty() &&
            director.isNullOrBlank() &&
            maleActor.isNullOrBlank() &&
            femaleActress.isNullOrBlank() &&
            studio.isNullOrBlank() &&
            romanceType == "Any" &&
            seriesType == "Any" &&
            (adultContent == "Any" || adultContent.isBlank())

    val activeFilterCount: Int
        get() {
            var count = 0
            if (sort != "Trending" && sort != "Default") count++
            if (format != "Any" && format != "All") count++
            if (status != "Any" && status != "All") count++
            if (episodes != "Any") count++
            if (minRating > 0.0) count++
            if (dubbedOnly) count++
            if (watchedOnly != "Show everything") count++
            if (year != "Any") count++
            if (season != "Any" && season != "All") count++
            if (genres.isNotEmpty()) count += genres.size
            if (tags.isNotEmpty()) count += tags.size
            if (!director.isNullOrBlank()) count++
            if (!maleActor.isNullOrBlank()) count++
            if (!femaleActress.isNullOrBlank()) count++
            if (!studio.isNullOrBlank()) count++
            if (romanceType != "Any") count++
            if (seriesType != "Any") count++
            if (adultContent != "Any" && adultContent.isNotBlank()) count++
            return count
        }
}
