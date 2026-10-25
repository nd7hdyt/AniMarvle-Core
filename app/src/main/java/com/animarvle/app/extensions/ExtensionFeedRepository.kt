package com.animarvle.app.extensions

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class ExtensionFeedRepository(
    private val repoRepository: ExtensionRepoRepository,
) {
    private val client = OkHttpClient()

    suspend fun fetchAllAvailable(): List<AvailableExtension> = withContext(Dispatchers.IO) {
        val repos = repoRepository.getAll()
        val allExtensions = mutableListOf<AvailableExtension>()

        for (repo in repos) {
            val list = fetchFromRepo(repo.baseUrl)
            allExtensions.addAll(list)
        }

        // Deduplicate by package name or ID, preferring higher version or first found
        allExtensions.distinctBy { it.pkg.ifBlank { it.id } }
    }

    suspend fun fetchFromRepo(rawBaseUrl: String): List<AvailableExtension> = withContext(Dispatchers.IO) {
        val normalized = normalizeRepoUrl(rawBaseUrl)
        val items = mutableListOf<AvailableExtension>()

        // 1. Try Aniyomi index.min.json
        val aniyomiUrl = if (normalized.endsWith(".json", ignoreCase = true)) normalized else "$normalized/index.min.json"
        val aniyomiResult = runCatching { fetchAniyomiIndex(aniyomiUrl, normalized) }.getOrNull()
        if (!aniyomiResult.isNullOrEmpty()) {
            items.addAll(aniyomiResult)
            return@withContext items
        }

        // 2. Try Mangayomi anime_index.json or direct json endpoint
        val mangayomiUrl = if (normalized.endsWith(".json", ignoreCase = true)) normalized else "$normalized/anime_index.json"
        val mangayomiResult = runCatching { fetchMangayomiIndex(mangayomiUrl, normalized) }.getOrNull()
        if (!mangayomiResult.isNullOrEmpty()) {
            items.addAll(mangayomiResult)
            return@withContext items
        }

        // 3. Try CloudStream repo.json or plugins.json (Redowan's BDIX, etc.)
        val csUrl = if (normalized.endsWith(".json", ignoreCase = true)) normalized else "$normalized/repo.json"
        val csResult = runCatching { fetchCloudStreamIndex(csUrl, normalized) }.getOrNull()
        if (!csResult.isNullOrEmpty()) {
            items.addAll(csResult)
            return@withContext items
        }

        items
    }

    private fun fetchCloudStreamIndex(url: String, baseUrl: String): List<AvailableExtension> {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val list = mutableListOf<AvailableExtension>()

            if (body.trimStart().startsWith("[")) {
                parseCloudStreamPluginArray(JSONArray(body), baseUrl, list)
            } else {
                val obj = JSONObject(body)
                val pluginLists = obj.optJSONArray("pluginLists")
                if (pluginLists != null) {
                    for (i in 0 until pluginLists.length()) {
                        val pUrl = pluginLists.getString(i)
                        runCatching {
                            client.newCall(Request.Builder().url(pUrl).build()).execute().use { pRes ->
                                if (pRes.isSuccessful) {
                                    val pBody = pRes.body?.string().orEmpty()
                                    if (pBody.trimStart().startsWith("[")) {
                                        parseCloudStreamPluginArray(JSONArray(pBody), baseUrl, list)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return list
        }
    }

    private fun parseCloudStreamPluginArray(arr: JSONArray, baseUrl: String, out: MutableList<AvailableExtension>) {
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val name = obj.optString("name", "")
            if (name.isBlank()) continue
            val internalName = obj.optString("internalName", name)
            val pluginUrl = obj.optString("url", "")
            val lang = obj.optString("language", "all")
            val ver = obj.optString("version", "1.0")
            val icon = obj.optString("iconUrl", "")
            val isNsfw = obj.optJSONArray("tvTypes")?.let { tvArr ->
                (0 until tvArr.length()).any { tvArr.optString(it).equals("NSFW", ignoreCase = true) }
            } ?: false

            out.add(
                AvailableExtension(
                    id = "cloudstream.$internalName",
                    name = name,
                    pkg = "cloudstream.$internalName",
                    versionName = ver,
                    versionCode = obj.optLong("version", 0),
                    lang = lang,
                    isNsfw = isNsfw,
                    iconUrl = icon.takeIf { it.isNotBlank() },
                    apkUrl = pluginUrl.takeIf { it.isNotBlank() },
                    sourceCodeUrl = obj.optString("repositoryUrl", null),
                    engine = ExtensionEngine.CLOUDSTREAM,
                    isMovieOrAnime = true,
                    repoBaseUrl = baseUrl,
                )
            )
        }
    }

    private fun fetchAniyomiIndex(url: String, baseUrl: String): List<AvailableExtension> {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val arr = JSONArray(body)
            val list = mutableListOf<AvailableExtension>()

            val apkBase = if (baseUrl.endsWith(".json", ignoreCase = true)) {
                baseUrl.substringBeforeLast('/')
            } else {
                baseUrl
            }

            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val pkg = obj.optString("pkg", "")
                val name = obj.optString("name", pkg)
                val apkName = obj.optString("apk", "")
                val lang = obj.optString("lang", "all")
                val ver = obj.optString("version", "")
                val code = obj.optLong("code", 0)
                val nsfw = obj.optInt("nsfw", 0) == 1

                if (pkg.isBlank()) continue

                val iconUrl = "$apkBase/icon/$pkg.png"
                val apkUrl = if (apkName.startsWith("http")) apkName else "$apkBase/apk/$apkName"

                list.add(
                    AvailableExtension(
                        id = pkg,
                        name = name,
                        pkg = pkg,
                        versionName = ver,
                        versionCode = code,
                        lang = lang,
                        isNsfw = nsfw,
                        iconUrl = iconUrl,
                        apkUrl = apkUrl,
                        engine = ExtensionEngine.ANIYOMI,
                        isMovieOrAnime = true,
                        repoBaseUrl = baseUrl,
                    )
                )
            }
            return list
        }
    }

    private fun fetchMangayomiIndex(url: String, baseUrl: String): List<AvailableExtension> {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val arr = JSONArray(body)
            val list = mutableListOf<AvailableExtension>()

            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("id", "")
                val name = obj.optString("name", "")
                val lang = obj.optString("lang", "all")
                val ver = obj.optString("version", "")
                val iconUrl = obj.optString("iconUrl", null)
                val sourceCodeUrl = obj.optString("sourceCodeUrl", null)
                val isNsfw = obj.optBoolean("isNsfw", false)
                val itemType = obj.optInt("itemType", 1) // 1 = anime

                if (name.isBlank()) continue

                list.add(
                    AvailableExtension(
                        id = id.ifBlank { name },
                        name = name,
                        pkg = "mangayomi.$id.$name",
                        versionName = ver,
                        versionCode = 0,
                        lang = lang,
                        isNsfw = isNsfw,
                        iconUrl = iconUrl,
                        apkUrl = null,
                        sourceCodeUrl = sourceCodeUrl,
                        engine = ExtensionEngine.MANGAYOMI,
                        isMovieOrAnime = itemType == 1,
                        repoBaseUrl = baseUrl,
                    )
                )
            }
            return list
        }
    }

    private fun normalizeRepoUrl(raw: String): String {
        var url = raw.trim().trimEnd('/')
        if (url.endsWith("/index.pb", ignoreCase = true)) {
            url = url.substringBeforeLast("/index.pb")
        }
        if (url.startsWith("https://github.com/", ignoreCase = true)) {
            val parts = url.removePrefix("https://github.com/").split('/')
            if (parts.size >= 2) {
                val owner = parts[0]
                val repo = parts[1]
                val branch = if (parts.size >= 4 && parts[2] == "tree") parts[3] else "repo"
                url = "https://raw.githubusercontent.com/$owner/$repo/$branch"
            }
        }
        return url
    }
}
