package com.animarvle.app.extensions

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class ExtensionRepoRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("extension_repos", Context.MODE_PRIVATE)

    private val client = OkHttpClient()

    suspend fun getAll(): List<ExtensionRepo> = withContext(Dispatchers.IO) {
        val raw = prefs.getString(KEY_REPOS, null)
        val list = if (raw == null) {
            mutableListOf()
        } else {
            runCatching {
                val arr = org.json.JSONArray(raw)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    ExtensionRepo(
                        baseUrl = o.getString("baseUrl"),
                        name = o.optString("name", o.getString("baseUrl")),
                        shortName = o.optString("shortName", null),
                        website = o.optString("website", ""),
                        signingKeyFingerprint = o.optString("signingKeyFingerprint", ""),
                    )
                }.filterNot { it.baseUrl.contains("Secozzi", ignoreCase = true) }
            }.getOrDefault(emptyList()).toMutableList()
        }

        val yuzonoUrl = "https://raw.githubusercontent.com/yuzono/anime-repo/repo"
        if (list.none { it.baseUrl.startsWith(yuzonoUrl) }) {
            list.add(
                0,
                ExtensionRepo(
                    baseUrl = yuzonoUrl,
                    name = "Yuzono Anime Extensions",
                    website = "https://github.com/yuzono/anime-repo",
                ),
            )
            save(list)
        }

        val punpunUrl = "https://raw.githubusercontent.com/punpunsx/aniyomi-extensions/repo"
        if (list.none { it.baseUrl.startsWith(punpunUrl) }) {
            list.add(
                ExtensionRepo(
                    baseUrl = punpunUrl,
                    name = "Punpun Aniyomi Extensions",
                    website = "https://github.com/punpunsx/aniyomi-extensions",
                ),
            )
            save(list)
        }
        list
    }

    suspend fun add(repo: ExtensionRepo): Result<ExtensionRepo> = withContext(Dispatchers.IO) {
        val repos = getAll().toMutableList()
        if (repos.any { it.baseUrl == repo.baseUrl }) {
            return@withContext Result.failure(Exception("Repo already exists"))
        }
        repos.add(repo)
        save(repos)
        Result.success(repo)
    }

    suspend fun remove(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        val repos = getAll().toMutableList()
        val removed = repos.removeAll { it.baseUrl == baseUrl }
        if (removed) save(repos)
        removed
    }

    suspend fun refresh(): List<ExtensionRepo> = withContext(Dispatchers.IO) {
        val repos = getAll()
        val refreshed = repos.mapNotNull { repo ->
            fetchRepoMeta(repo.baseUrl)?.let { meta ->
                repo.copy(
                    name = meta.name ?: repo.name,
                    shortName = meta.shortName ?: repo.shortName,
                    website = meta.website ?: repo.website,
                    signingKeyFingerprint = meta.signingKeyFingerprint ?: repo.signingKeyFingerprint,
                )
            }
        }
        if (refreshed.isNotEmpty()) save(refreshed)
        refreshed
    }

    suspend fun fetchRepoMeta(baseUrl: String): RepoMeta? = withContext(Dispatchers.IO) {
        val normalized = normalizeRepoUrl(baseUrl)
        // Try repo.json first, then index.min.json if repo.json doesn't exist
        fetchMetaFromEndpoint("$normalized/repo.json")
            ?: fetchMetaFromEndpoint("$normalized/index.min.json")
            ?: RepoMeta(
                name = normalized.substringAfterLast('/').ifBlank { normalized },
                shortName = null,
                website = normalized,
                signingKeyFingerprint = null,
            )
    }

    private fun normalizeRepoUrl(raw: String): String {
        var url = raw.trim().trimEnd('/')
        // If user entered .../repo.json or .../index.min.json, strip the filename
        if (url.endsWith("/repo.json", ignoreCase = true)) {
            url = url.substringBeforeLast("/repo.json")
        } else if (url.endsWith("/index.min.json", ignoreCase = true)) {
            url = url.substringBeforeLast("/index.min.json")
        }

        // Convert standard GitHub web URLs to raw GitHub URLs if needed
        // e.g. https://github.com/user/repo/tree/branch -> https://raw.githubusercontent.com/user/repo/branch
        // or https://github.com/user/repo -> https://raw.githubusercontent.com/user/repo/repo
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

    private fun fetchMetaFromEndpoint(endpointUrl: String): RepoMeta? {
        return runCatching {
            val request = Request.Builder()
                .url(endpointUrl)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val body = response.body?.string() ?: return@runCatching null
                val json = JSONObject(body)
                val metaObj = json.optJSONObject("meta")
                val name = metaObj?.optString("name", null)
                    ?: json.optString("name", null)
                val shortName = metaObj?.optString("shortName", null)
                    ?: json.optString("shortName", null)
                val website = metaObj?.optString("website", null)
                    ?: json.optString("website", null)
                val fingerprint = metaObj?.optString("signingKeyFingerprint", null)
                    ?: json.optString("signingKeyFingerprint", null)

                if (name == null && metaObj == null && !json.has("meta")) return@runCatching null

                RepoMeta(
                    name = name,
                    shortName = shortName,
                    website = website,
                    signingKeyFingerprint = fingerprint,
                )
            }
        }.getOrNull()
    }

    private fun save(repos: List<ExtensionRepo>) {
        val arr = org.json.JSONArray()
        repos.forEach { repo ->
            arr.put(JSONObject().apply {
                put("baseUrl", repo.baseUrl)
                put("name", repo.name)
                put("shortName", repo.shortName ?: JSONObject.NULL)
                put("website", repo.website)
                put("signingKeyFingerprint", repo.signingKeyFingerprint)
            })
        }
        prefs.edit().putString(KEY_REPOS, arr.toString()).apply()
    }

    data class RepoMeta(
        val name: String?,
        val shortName: String?,
        val website: String?,
        val signingKeyFingerprint: String?,
    )

    companion object {
        private const val KEY_REPOS = "repos"
    }
}
