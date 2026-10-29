package com.animarvle.app.data.tmdb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class TmdbApi(
    private val client: OkHttpClient = defaultClient(),
    var apiKey: String = DEFAULT_API_KEY,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    suspend fun trendingAll(page: Int = 1): List<TmdbItem> =
        get("/trending/all/day?api_key=$apiKey&page=$page")

    suspend fun trendingMovies(page: Int = 1): List<TmdbItem> =
        get("/trending/movie/day?api_key=$apiKey&page=$page")

    suspend fun trendingTv(page: Int = 1): List<TmdbItem> =
        get("/trending/tv/day?api_key=$apiKey&page=$page")

    suspend fun popularMovies(page: Int = 1): List<TmdbItem> =
        get("/movie/popular?api_key=$apiKey&page=$page")

    suspend fun popularTv(page: Int = 1): List<TmdbItem> =
        get("/tv/popular?api_key=$apiKey&page=$page")

    suspend fun topRatedMovies(page: Int = 1): List<TmdbItem> =
        get("/movie/top_rated?api_key=$apiKey&page=$page")

    suspend fun topRatedTv(page: Int = 1): List<TmdbItem> =
        get("/tv/top_rated?api_key=$apiKey&page=$page")

    suspend fun nowPlayingMovies(page: Int = 1): List<TmdbItem> =
        get("/movie/now_playing?api_key=$apiKey&page=$page")

    suspend fun popularCartoons(page: Int = 1): List<TmdbItem> =
        get("/discover/tv?api_key=$apiKey&with_genres=16&with_original_language=en&sort_by=popularity.desc&page=$page")

    suspend fun popularKDramas(page: Int = 1): List<TmdbItem> =
        get("/discover/tv?api_key=$apiKey&with_original_language=ko&with_genres=18&sort_by=popularity.desc&page=$page")

    suspend fun popularCDramas(page: Int = 1): List<TmdbItem> =
        get("/discover/tv?api_key=$apiKey&with_original_language=zh&with_genres=18&sort_by=popularity.desc&page=$page")

    suspend fun discover(
        type: String, // "movie" or "tv"
        page: Int = 1,
        watchProviders: String? = null,
        watchRegion: String = "US",
        withGenres: String? = null,
        withOriginalLanguage: String? = null,
        sortBy: String = "popularity.desc"
    ): List<TmdbItem> {
        val params = StringBuilder("/discover/$type?api_key=$apiKey&page=$page&sort_by=$sortBy")
        if (!watchProviders.isNullOrBlank()) {
            params.append("&with_watch_providers=$watchProviders&watch_region=$watchRegion&with_watch_monetization_types=flatrate")
        }
        if (!withGenres.isNullOrBlank()) {
            params.append("&with_genres=$withGenres")
        }
        if (!withOriginalLanguage.isNullOrBlank()) {
            params.append("&with_original_language=$withOriginalLanguage")
        }
        return get(params.toString())
    }

    suspend fun searchMulti(query: String, page: Int = 1): List<TmdbItem> {
        val encoded = withContext(Dispatchers.IO) { URLEncoder.encode(query, "UTF-8") }
        return get("/search/multi?api_key=$apiKey&query=$encoded&page=$page")
    }

    suspend fun movieDetails(id: Long): TmdbItem? =
        getItem("/movie/$id?api_key=$apiKey")

    suspend fun tvDetails(id: Long): TmdbItem? =
        getItem("/tv/$id?api_key=$apiKey")

    suspend fun movieRecommendations(id: Long): List<TmdbItem> =
        runCatching { get("/movie/$id/recommendations?api_key=$apiKey") }.getOrDefault(emptyList())

    suspend fun tvRecommendations(id: Long): List<TmdbItem> =
        runCatching { get("/tv/$id/recommendations?api_key=$apiKey") }.getOrDefault(emptyList())

    suspend fun scheduleTv(dateIso: String, page: Int = 1): Pair<Int, List<TmdbItem>> =
        withContext(Dispatchers.IO) {
            val url = "$BASE_URL/discover/tv?api_key=$apiKey&air_date.gte=$dateIso&air_date.lte=$dateIso&sort_by=popularity.desc&page=$page"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) {
                    throw IOException("TMDB HTTP ${response.code}: ${text.take(200)}")
                }
                val parsed = json.decodeFromString(TmdbPage.serializer(TmdbItem.serializer()), text)
                (parsed.totalResults) to parsed.results
            }
        }

    suspend fun seasonEpisodes(tvId: Long, seasonNumber: Int = 1): List<TmdbEpisode> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$BASE_URL/tv/$tvId/season/$seasonNumber?api_key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching emptyList()
                val text = response.body.string()
                json.decodeFromString(TmdbSeasonDetail.serializer(), text).episodes
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun getItem(path: String): TmdbItem? = withContext(Dispatchers.IO) {
        val url = if (path.startsWith("http")) path else "$BASE_URL$path"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val text = response.body.string()
            json.decodeFromString(TmdbItem.serializer(), text)
        }
    }

    private suspend fun get(path: String): List<TmdbItem> = withContext(Dispatchers.IO) {
        val url = if (path.startsWith("http")) path else "$BASE_URL$path"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                throw IOException("TMDB HTTP ${response.code}: ${text.take(200)}")
            }
            val parsed = json.decodeFromString(TmdbPage.serializer(TmdbItem.serializer()), text)
            parsed.results
        }
    }

    companion object {
        const val BASE_URL = "https://api.themoviedb.org/3"
        val DEFAULT_API_KEY: String = com.animarvle.app.BuildConfig.TMDB_API_KEY.ifBlank {
            "db4ff5546d1b35981cc4715cdd5f27f7"
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
