package com.animarvle.app.data.updater

import java.io.Serializable

data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val releaseNotes: String,
    val githubUrl: String = "https://github.com/nd7hdyt/AniMarvle/releases",
    val hasUpdate: Boolean = false,
) : Serializable
