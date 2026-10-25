package com.animarvle.app.data.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.animarvle.app.BuildConfig
import com.animarvle.app.ui.profile.PlayerSettingsFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

object AppUpdateRepository {

    const val KEY_CHECK_UPDATES_ON_LAUNCH = "check_updates_on_launch"
    const val KEY_SKIPPED_UPDATE_VERSION = "skipped_update_version"
    const val KEY_LAST_UPDATE_CHECK = "last_update_check_time"

    private val VERSION_JSON_URLS = listOf(
        "https://raw.githubusercontent.com/nd7hdyt/AniMarvle/main/version.json",
        "https://gitlab.com/nd7hdyt/Animarvle/-/raw/main/version.json",
        "https://raw.githubusercontent.com/nd7hdyt/Animarvle/main/version.json",
    )
    private const val GITHUB_RELEASES_API =
        "https://api.github.com/repos/nd7hdyt/AniMarvle/releases/latest"

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    fun isCheckOnLaunchEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PlayerSettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_CHECK_UPDATES_ON_LAUNCH, true)
    }

    fun setCheckOnLaunchEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PlayerSettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_CHECK_UPDATES_ON_LAUNCH, enabled).apply()
    }

    fun isVersionSkipped(context: Context, versionCode: Int): Boolean {
        val prefs = context.getSharedPreferences(PlayerSettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_SKIPPED_UPDATE_VERSION, -1) == versionCode
    }

    fun skipVersion(context: Context, versionCode: Int) {
        val prefs = context.getSharedPreferences(PlayerSettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_SKIPPED_UPDATE_VERSION, versionCode).apply()
    }

    fun recordCheckTime(context: Context) {
        val prefs = context.getSharedPreferences(PlayerSettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, System.currentTimeMillis()).apply()
    }

    suspend fun checkForUpdate(context: Context): Result<AppUpdateInfo> = withContext(Dispatchers.IO) {
        recordCheckTime(context)

        // 1. Try Primary version.json candidates
        val primaryResult = fetchFromVersionJson()
        if (primaryResult.isSuccess) {
            val info = primaryResult.getOrThrow()
            if (info != null) {
                return@withContext Result.success(info)
            }
        }

        // 2. Fallback to GitHub Releases API
        val githubResult = fetchFromGitHubReleases()
        if (githubResult.isSuccess) {
            val info = githubResult.getOrThrow()
            if (info != null) {
                return@withContext Result.success(info)
            }
        }

        // Default: No updates found or up to date
        Result.success(
            AppUpdateInfo(
                versionCode = BuildConfig.VERSION_CODE,
                versionName = BuildConfig.VERSION_NAME,
                apkUrl = "",
                releaseNotes = "",
                hasUpdate = false,
            )
        )
    }

    private fun fetchFromVersionJson(): Result<AppUpdateInfo?> {
        return runCatching {
            for (url in VERSION_JSON_URLS) {
                try {
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "Animarvle-App/${BuildConfig.VERSION_NAME}")
                        .build()

                    val response = httpClient.newCall(request).execute()
                    if (!response.isSuccessful) continue

                    val body = response.body.string()
                    val json = JSONObject(body)

                    val remoteVersionCode = json.optInt("version_code", 0)
                    val remoteVersionName = json.optString("version_name", "")
                    val apkUrl = json.optString("apk_url", "")
                    val releaseNotes = json.optString("release_notes", "")
                    val githubUrl = json.optString("github_url", "https://github.com/nd7hdyt/AniMarvle/releases")

                    val currentCode = BuildConfig.VERSION_CODE
                    val hasUpdate = remoteVersionCode > currentCode ||
                        (remoteVersionCode == 0 && remoteVersionName.isNotBlank() && remoteVersionName != BuildConfig.VERSION_NAME)

                    return@runCatching AppUpdateInfo(
                        versionCode = remoteVersionCode,
                        versionName = remoteVersionName.ifBlank { "v$remoteVersionCode" },
                        apkUrl = apkUrl,
                        releaseNotes = releaseNotes,
                        githubUrl = githubUrl,
                        hasUpdate = hasUpdate,
                    )
                } catch (_: Exception) {
                    continue
                }
            }
            null
        }
    }

    private fun fetchFromGitHubReleases(): Result<AppUpdateInfo?> {
        return runCatching {
            val request = Request.Builder()
                .url(GITHUB_RELEASES_API)
                .header("User-Agent", "Animarvle-App/${BuildConfig.VERSION_NAME}")
                .header("Accept", "application/vnd.github.v3+json")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return@runCatching null

            val body = response.body?.string() ?: return@runCatching null
            val json = JSONObject(body)

            val tagName = json.optString("tag_name", "").removePrefix("v").trim()
            val releaseName = json.optString("name", tagName)
            val releaseNotes = json.optString("body", "")
            val htmlUrl = json.optString("html_url", "https://github.com/nd7hdyt/AniMarvle/releases")

            var apkUrl = ""
            val assets = json.optJSONArray("assets") ?: JSONArray()
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url", "")
                    break
                }
            }

            val currentName = BuildConfig.VERSION_NAME.removePrefix("v").trim()
            val hasUpdate = isNewerVersion(tagName, currentName)

            AppUpdateInfo(
                versionCode = extractVersionCode(tagName),
                versionName = if (tagName.isNotBlank()) "v$tagName" else releaseName,
                apkUrl = apkUrl,
                releaseNotes = releaseNotes,
                githubUrl = htmlUrl,
                hasUpdate = hasUpdate,
            )
        }
    }

    private fun isNewerVersion(remote: String, current: String): Boolean {
        if (remote.isBlank() || current.isBlank()) return false
        val rParts = remote.split('.').mapNotNull { it.filter { c -> c.isDigit() }.toIntOrNull() }
        val cParts = current.split('.').mapNotNull { it.filter { c -> c.isDigit() }.toIntOrNull() }
        val maxLen = maxOf(rParts.size, cParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val c = cParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    private fun extractVersionCode(tag: String): Int {
        val digits = tag.filter { it.isDigit() }
        return digits.toIntOrNull() ?: 0
    }

    fun downloadApk(
        context: Context,
        apkUrl: String,
        versionName: String,
        onProgress: (percent: Int, downloaded: Long, total: Long) -> Unit,
        onComplete: (File) -> Unit,
        onError: (Throwable) -> Unit,
    ): Call {
        val updatesDir = File(context.cacheDir, "updates")
        if (!updatesDir.exists()) updatesDir.mkdirs()

        val apkFile = File(updatesDir, "Animarvle-${versionName.replace('/', '_')}.apk")

        val request = Request.Builder()
            .url(apkUrl)
            .header("User-Agent", "Animarvle-App/${BuildConfig.VERSION_NAME}")
            .build()

        val call = httpClient.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    onError(IOException("HTTP error code ${response.code}"))
                    return
                }

                val body = response.body
                if (body == null) {
                    onError(IOException("Response body is null"))
                    return
                }

                try {
                    val contentLength = body.contentLength()
                    val inputStream = body.byteStream()
                    val outputStream = FileOutputStream(apkFile)

                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead: Long = 0

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (contentLength > 0) {
                            val percent = ((totalRead * 100) / contentLength).toInt().coerceIn(0, 100)
                            onProgress(percent, totalRead, contentLength)
                        }
                    }

                    outputStream.flush()
                    outputStream.close()
                    inputStream.close()

                    onComplete(apkFile)
                } catch (t: Throwable) {
                    onError(t)
                }
            }
        })
        return call
    }

    fun installApk(context: Context, apkFile: File) {
        val authority = "${context.packageName}.provider"
        val apkUri = FileProvider.getUriForFile(context, authority, apkFile)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
