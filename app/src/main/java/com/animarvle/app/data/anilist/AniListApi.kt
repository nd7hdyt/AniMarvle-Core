package com.animarvle.app.data.anilist

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * AniList GraphQL client (ARCH §10). Metadata only.
 * Every query here was executed against https://graphql.anilist.co on 2026-10-11
 * and the responses are recorded in docs/VERSIONS.md.
 */
class AniListApi(
    private val client: OkHttpClient = defaultClient(),
    private val isAdultProvider: () -> Boolean = { false },
) {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    suspend fun trending(page: Int = 1, perPage: Int = 50): List<Media> =
        pageQuery(trendingQuery(isAdultProvider()), page, perPage).media

    suspend fun topAiring(page: Int = 1, perPage: Int = 20): List<Media> =
        pageQuery(topAiringQuery(isAdultProvider()), page, perPage).media

    suspend fun airingNow(page: Int = 1, perPage: Int = 20): List<Media> =
        pageQuery(topAiringQuery(isAdultProvider()), page, perPage).media

    suspend fun popularSeason(season: String, seasonYear: Int, page: Int = 1, perPage: Int = 20): List<Media> =
        post(
            popularSeasonQuery(isAdultProvider()),
            mapOf(
                "page" to page.toString(),
                "perPage" to perPage.toString(),
                "season" to season,
                "seasonYear" to seasonYear.toString(),
            ),
            TrendingData.serializer(),
        ).Page?.media.orEmpty()

    suspend fun allTimePopular(page: Int = 1, perPage: Int = 20): List<Media> =
        pageQuery(allTimePopularQuery(isAdultProvider()), page, perPage).media

    suspend fun topRated(page: Int = 1, perPage: Int = 20): List<Media> =
        pageQuery(topRatedQuery(isAdultProvider()), page, perPage).media

    suspend fun mostFavorited(page: Int = 1, perPage: Int = 20): List<Media> =
        pageQuery(mostFavoritedQuery(isAdultProvider()), page, perPage).media

    suspend fun popularMovies(page: Int = 1, perPage: Int = 20): List<Media> =
        pageQuery(popularMoviesQuery(isAdultProvider()), page, perPage).media

    suspend fun donghua(page: Int = 1, perPage: Int = 20): List<Media> =
        pageQuery(donghuaQuery(isAdultProvider()), page, perPage).media

    suspend fun search(query: String, page: Int = 1, perPage: Int = 50, includeAdult: Boolean = false): List<Media> =
        post(
            searchQuery(includeAdult || isAdultProvider()),
            buildMap {
                put("page", page.toString())
                put("perPage", perPage.toString())
                put("query", query)
            },
            TrendingData.serializer(),
        ).Page?.media.orEmpty()

    suspend fun discoverAnime(
        page: Int = 1,
        perPage: Int = 50,
        genre: String? = null,
        format: String? = null,
        year: Int? = null,
        sort: String = "POPULARITY_DESC",
        includeAdult: Boolean = false,
        isAdultOnly: Boolean = false,
    ): List<Media> {
        val adultFilterStr = when {
            isAdultOnly -> ", isAdult: true"
            includeAdult || isAdultProvider() -> ""
            else -> ", isAdult: false"
        }
        val genreFilterStr = if (!genre.isNullOrBlank()) ", genre: \"$genre\"" else ""
        val formatFilterStr = if (!format.isNullOrBlank()) ", format: $format" else ""
        val yearFilterStr = if (year != null && year > 0) ", seasonYear: $year" else ""

        val query = """
            query (${'$'}page: Int, ${'$'}perPage: Int) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                pageInfo { currentPage hasNextPage total }
                media(type: ANIME, sort: $sort$genreFilterStr$formatFilterStr$yearFilterStr$adultFilterStr) { $MEDIA_FIELDS }
              }
            }
        """.trimIndent()
        return pageQuery(query, page, perPage).media
    }

    suspend fun details(id: Long): Media? =
        post(DETAILS, mapOf("id" to id.toString()), MediaData.serializer()).Media

    suspend fun studioAnime(studioName: String, page: Int = 1, perPage: Int = 25): List<Media> = runCatching {
        val query = """
            query (${'$'}studio: String, ${'$'}page: Int, ${'$'}perPage: Int) {
              Studio(search: ${'$'}studio) {
                media(sort: POPULARITY_DESC, page: ${'$'}page, perPage: ${'$'}perPage) {
                  nodes { $MEDIA_FIELDS }
                }
              }
            }
        """.trimIndent()
        post(
            query,
            mapOf("studio" to studioName, "page" to page.toString(), "perPage" to perPage.toString()),
            StudioData.serializer(),
        ).Studio?.media?.nodes.orEmpty()
    }.getOrElse { emptyList() }

    suspend fun recommendations(id: Long): List<Media> =
        post(DETAILS, mapOf("id" to id.toString()), MediaData.serializer())
            .Media?.recommendations?.nodes.orEmpty()
            .mapNotNull { it.mediaRecommendation }

    suspend fun schedule(fromEpochSec: Long, toEpochSec: Long, perPage: Int = 50): List<AiringNode> =
        post(
            SCHEDULE,
            mapOf(
                "from" to fromEpochSec.toString(),
                "to" to toEpochSec.toString(),
                "perPage" to perPage.toString(),
            ),
            ScheduleData.serializer(),
        ).Page?.airingSchedules.orEmpty()

    private suspend fun pageQuery(
        query: String,
        page: Int,
        perPage: Int,
    ): MediaPage = post(
        query,
        mapOf("page" to page.toString(), "perPage" to perPage.toString()),
        TrendingData.serializer(),
    ).Page ?: MediaPage()

    private suspend fun <T> post(
        query: String,
        variables: Map<String, String>,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): T = withContext(Dispatchers.IO) {
        val body = json.encodeToString(
            GqlRequest.serializer(),
            GqlRequest(query = query, variables = variables),
        ).toRequestBody(JSON_MEDIA)

        val request = Request.Builder()
            .url(ENDPOINT)
            .post(body)
            .header("Accept", "application/json")
            .header("User-Agent", "AnimarvleApp/1.0 (Android; Linux)")
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("AniList HTTP ${response.code}: ${text.take(200)}")
            }
            val parsed = json.decodeFromString(GqlResponse.serializer(serializer), text)
            parsed.errors?.firstOrNull()?.message?.let { throw IOException("AniList: $it") }
            parsed.data ?: throw IOException("AniList returned no data")
        }
    }

    companion object {
        const val ENDPOINT = "https://graphql.anilist.co"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        // Query shapes verified against the live API on 2026-10-11.
        private const val MEDIA_FIELDS = """
            id idMal countryOfOrigin
            title { romaji english native }
            synonyms status format episodes season seasonYear
            startDate { year month day }
            coverImage { extraLarge large color }
            bannerImage genres averageScore description(asHtml: false) duration isAdult
            nextAiringEpisode { episode airingAt timeUntilAiring }
        """

        private fun adultFilter(includeAdult: Boolean): String = if (includeAdult) "" else ", isAdult: false"

        private fun trendingQuery(includeAdult: Boolean) = """
            query (${'$'}page: Int, ${'$'}perPage: Int) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                pageInfo { currentPage hasNextPage total }
                media(type: ANIME, sort: TRENDING_DESC${adultFilter(includeAdult)}) { $MEDIA_FIELDS }
              }
            }
        """

        private fun topAiringQuery(includeAdult: Boolean) = """
            query (${'$'}page: Int, ${'$'}perPage: Int) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                pageInfo { currentPage hasNextPage total }
                media(type: ANIME, sort: POPULARITY_DESC, status: RELEASING${adultFilter(includeAdult)}) { $MEDIA_FIELDS }
              }
            }
        """

        private fun popularSeasonQuery(includeAdult: Boolean) = """
            query (${'$'}page: Int, ${'$'}perPage: Int, ${'$'}season: MediaSeason, ${'$'}seasonYear: Int) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                pageInfo { currentPage hasNextPage total }
                media(type: ANIME, season: ${'$'}season, seasonYear: ${'$'}seasonYear, sort: POPULARITY_DESC${adultFilter(includeAdult)}) { $MEDIA_FIELDS }
              }
            }
        """

        private fun allTimePopularQuery(includeAdult: Boolean) = """
            query (${'$'}page: Int, ${'$'}perPage: Int) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                pageInfo { currentPage hasNextPage total }
                media(type: ANIME, sort: POPULARITY_DESC${adultFilter(includeAdult)}) { $MEDIA_FIELDS }
              }
            }
        """

        private fun topRatedQuery(includeAdult: Boolean) = """
            query (${'$'}page: Int, ${'$'}perPage: Int) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                pageInfo { currentPage hasNextPage total }
                media(type: ANIME, sort: SCORE_DESC${adultFilter(includeAdult)}) { $MEDIA_FIELDS }
              }
            }
        """

        private fun mostFavoritedQuery(includeAdult: Boolean) = """
            query (${'$'}page: Int, ${'$'}perPage: Int) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                pageInfo { currentPage hasNextPage total }
                media(type: ANIME, sort: FAVOURITES_DESC${adultFilter(includeAdult)}) { $MEDIA_FIELDS }
              }
            }
        """

        private fun popularMoviesQuery(includeAdult: Boolean) = """
            query (${'$'}page: Int, ${'$'}perPage: Int) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                pageInfo { currentPage hasNextPage total }
                media(type: ANIME, format: MOVIE, sort: POPULARITY_DESC${adultFilter(includeAdult)}) { $MEDIA_FIELDS }
              }
            }
        """

        private fun donghuaQuery(includeAdult: Boolean) = """
            query (${'$'}page: Int, ${'$'}perPage: Int) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                pageInfo { currentPage hasNextPage total }
                media(type: ANIME, countryOfOrigin: "CN", sort: [TRENDING_DESC, POPULARITY_DESC]${adultFilter(includeAdult)}) { $MEDIA_FIELDS }
              }
            }
        """

        private fun searchQuery(includeAdult: Boolean) = """
            query (${'$'}page: Int, ${'$'}perPage: Int, ${'$'}query: String) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                pageInfo { currentPage hasNextPage total }
                media(type: ANIME, search: ${'$'}query${adultFilter(includeAdult)}) { $MEDIA_FIELDS }
              }
            }
        """

        private const val RELATION_NODE_FIELDS = """
            id format episodes
            title { romaji english native }
            startDate { year month day }
            coverImage { extraLarge large color }
        """

        private const val DETAILS = """
            query (${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) {
                $MEDIA_FIELDS
                recommendations(sort: RATING_DESC, perPage: 12) {
                  nodes { rating mediaRecommendation { $MEDIA_FIELDS } }
                }
                relations {
                  edges {
                    relationType
                    node {
                      $RELATION_NODE_FIELDS
                      relations {
                        edges {
                          relationType
                          node {
                            $RELATION_NODE_FIELDS
                            relations {
                              edges {
                                relationType
                                node {
                                  $RELATION_NODE_FIELDS
                                  relations {
                                    edges {
                                      relationType
                                      node { $RELATION_NODE_FIELDS }
                                    }
                                  }
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
        """

        // AiringSort.TIME_DESC verified via schema introspection (AIRING_AT_DESC is invalid).
        private const val SCHEDULE = """
            query (${'$'}from: Int, ${'$'}to: Int, ${'$'}perPage: Int) {
              Page(page: 1, perPage: ${'$'}perPage) {
                airingSchedules(airingAt_greater: ${'$'}from, airingAt_lesser: ${'$'}to, sort: TIME_DESC) {
                  airingAt episode
                  media { $MEDIA_FIELDS }
                }
              }
            }
        """
    }
}