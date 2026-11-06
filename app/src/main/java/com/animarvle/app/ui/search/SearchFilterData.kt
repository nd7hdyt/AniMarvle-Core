package com.animarvle.app.ui.search

data class PopupItem(
    val title: String,
    val isHeader: Boolean = false,
)

/**
 * Curated filter dictionaries, categories, and identifier mappings
 * for both Anime (AniList / MAL) and Movies & TV (TMDB / Simkl).
 */
object SearchFilterData {

    val YEAR_POPUP_ITEMS = listOf(
        PopupItem("Any"),
        PopupItem("Decades", isHeader = true),
        PopupItem("2020s"),
        PopupItem("2010s"),
        PopupItem("2000s"),
        PopupItem("1990s"),
        PopupItem("1980s"),
        PopupItem("Earlier"),
        PopupItem("Years", isHeader = true),
        PopupItem("2026"),
        PopupItem("2025"),
        PopupItem("2024"),
        PopupItem("2023"),
        PopupItem("2022"),
        PopupItem("2021"),
        PopupItem("2020"),
        PopupItem("2019"),
        PopupItem("2018"),
        PopupItem("2017"),
        PopupItem("2016"),
        PopupItem("2015"),
        PopupItem("2014"),
        PopupItem("2013"),
        PopupItem("2012"),
        PopupItem("2011"),
        PopupItem("2010"),
    )

    val SORT_OPTIONS = listOf(
        "Trending",
        "Most popular",
        "Highest rated",
        "Most favorited",
        "Newest",
        "Oldest",
        "Most episodes",
        "A-Z",
    )

    val FORMAT_OPTIONS = listOf(
        "Any",
        "Movie",
        "TV Series",
        "Anime TV",
        "OVA / ONA",
        "Special",
    )

    val STATUS_OPTIONS = listOf(
        "Any",
        "Airing / Releasing",
        "Finished",
        "Upcoming",
    )

    val EPISODE_OPTIONS = listOf(
        "Any",
        "1-12 episodes",
        "13-24 episodes",
        "25-50 episodes",
        "50+ episodes",
    )

    val RATING_OPTIONS = listOf(
        "Any",
        "9.0+ ⭐",
        "8.5+ ⭐",
        "8.0+ ⭐",
        "7.5+ ⭐",
        "7.0+ ⭐",
        "6.0+ ⭐",
    )

    val WATCH_OPTIONS = listOf(
        "Show everything",
        "Unwatched only",
    )

    val YEAR_OPTIONS = listOf(
        "Any",
        "2026",
        "2025",
        "2024",
        "2023",
        "2022",
        "2020-2029",
        "2010-2019",
        "2000-2009",
        "1990s",
        "1980s",
    )

    val SEASON_OPTIONS = listOf(
        "Any",
        "Winter",
        "Spring",
        "Summer",
        "Fall",
    )

    val MOVIE_GENRES = mapOf(
        "Action" to 28,
        "Adventure" to 12,
        "Animation" to 16,
        "Comedy" to 35,
        "Crime & Gangster" to 80,
        "Documentary" to 99,
        "Drama" to 18,
        "Adult (18+)" to -1,
        "Adult" to -3,
        "Family" to 10751,
        "Fantasy" to 14,
        "Historical" to 36,
        "Horror" to 27,
        "Music" to 10402,
        "Musical & Dance" to 10402,
        "Mystery & Thriller" to 9648,
        "Romance" to 10749,
        "Sci-Fi" to 878,
        "Sport" to 99,
        "Superhero" to 28,
        "War" to 10752,
        "Western" to 37,
        "Biography" to 99,
        "Inspiring" to 18,
    )

    val ANIME_GENRES = listOf(
        "Action",
        "Adventure",
        "Comedy",
        "Drama",
        "Ecchi",
        "Fantasy",
        "Harem",
        "Hentai",
        "Isekai",
        "Mecha",
        "Music",
        "Mystery",
        "Psychological",
        "Romance",
        "Sci-Fi",
        "Seinen",
        "Shounen",
        "Slice of Life",
        "Sports",
        "Supernatural",
        "Thriller",
    )

    val DIRECTORS = mapOf(
        "Christopher Nolan" to 525L,
        "Quentin Tarantino" to 138L,
        "Martin Scorsese" to 1032L,
        "Steven Spielberg" to 488L,
        "Stanley Kubrick" to 240L,
        "Alfred Hitchcock" to 2636L,
        "David Fincher" to 7467L,
        "Denis Villeneuve" to 137427L,
        "Francis Ford Coppola" to 1776L,
        "Ridley Scott" to 578L,
        "James Cameron" to 2710L,
        "Peter Jackson" to 108L,
        "Guillermo del Toro" to 10828L,
        "Hayao Miyazaki" to 608L,
        "Akira Kurosawa" to 5026L,
        "Clint Eastwood" to 190L,
        "Wes Anderson" to 5655L,
        "Robert Zemeckis" to 24L,
        "Spike Lee" to 5281L,
        "Danny Boyle" to 203L,
    )

    val MALE_ACTORS = mapOf(
        "Timothée Chalamet" to 1190668L,
        "Leonardo DiCaprio" to 6193L,
        "Cillian Murphy" to 2037L,
        "Brad Pitt" to 287L,
        "Christian Bale" to 3894L,
        "Keanu Reeves" to 6384L,
        "Tom Hanks" to 31L,
        "Robert Downey Jr." to 3223L,
        "Ryan Gosling" to 30614L,
        "Pedro Pascal" to 1253360L,
        "Denzel Washington" to 5292L,
        "Morgan Freeman" to 192L,
        "Matthew McConaughey" to 10297L,
        "Samuel L. Jackson" to 2231L,
        "Tom Cruise" to 500L,
        "Henry Cavill" to 73968L,
        "Joaquin Phoenix" to 73421L,
        "Robert De Niro" to 380L,
        "Al Pacino" to 1158L,
        "Idris Elba" to 17605L,
    )

    val FEMALE_ACTRESSES = mapOf(
        "Florence Pugh" to 1373737L,
        "Scarlett Johansson" to 1245L,
        "Margot Robbie" to 234352L,
        "Zendaya" to 505710L,
        "Emma Stone" to 54693L,
        "Jennifer Lawrence" to 72129L,
        "Natalie Portman" to 524L,
        "Anya Taylor-Joy" to 1397778L,
        "Sydney Sweeney" to 115440L,
        "Jenna Ortega" to 974169L,
        "Anne Hathaway" to 1813L,
        "Charlize Theron" to 6885L,
        "Sandra Bullock" to 18277L,
        "Julia Roberts" to 1204L,
        "Nicole Kidman" to 2227L,
        "Ana de Armas" to 224513L,
        "Cate Blanchett" to 112L,
        "Meryl Streep" to 5064L,
        "Emily Blunt" to 5081L,
        "Angelina Jolie" to 11701L,
    )

    val STUDIOS = mapOf(
        "A24" to 41077L,
        "Marvel Studios" to 420L,
        "Warner Bros." to 174L,
        "Universal Pictures" to 33L,
        "Walt Disney Pictures" to 2L,
        "Columbia Pictures" to 5L,
        "Paramount Pictures" to 4L,
        "20th Century Studios" to 25L,
        "Blumhouse" to 3172L,
        "MAPPA" to 3959L,
        "Ufotable" to 5885L,
        "Madhouse" to 415L,
        "Bones" to 417L,
        "Wit Studio" to 6197L,
        "Kyoto Animation" to 414L,
        "CloverWorks" to 100788L,
        "Toei Animation" to 418L,
        "A-1 Pictures" to 416L,
    )

    val ROMANCE_TYPES = listOf(
        "Any",
        "Romantic Comedy (Rom-Com)",
        "Slow Burn Romance",
        "Enemies to Lovers",
        "Second Chance Romance",
        "Childhood Friends",
        "Love Triangle",
        "Forbidden Love",
        "Office / Workplace Romance",
        "Fantasy / Supernatural Romance",
        "Melodrama / Tearjerker",
    )

    val SERIES_TYPES = listOf(
        "Any",
        "TV Series (Multi-Season)",
        "Limited Series / Miniseries",
        "K-Drama (Korean Drama)",
        "C-Drama (Chinese Drama)",
        "Anime TV Series",
        "Western Cartoon",
        "Anthology Series",
        "Tokusatsu (Live Action)",
    )

    val ADULT_OPTIONS = listOf(
        "Any",
        "Adult (18+)",
        "Adult",
        "Ecchi",
        "Hentai",
        "Family Friendly (Safe)",
    )
}
