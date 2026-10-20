package com.animarvle.app.extensions

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log

/**
 * Discovers Aniyomi/Mihon extension packages installed on the device.
 *
 * Contract verified 2026-10-11 from `eu.kanade.tachiyomi.animeextension.en.animepahe` v14.45:
 * an extension declares `<uses-feature android:name="tachiyomi.animeextension" />` and
 * `<meta-data android:name="tachiyomi.animeextension.class" android:value=".AnimePahe" />`.
 *
 * Discovery only - see [TachiyomiExtensionLoader] for the classloading step, and the
 * "Source strategy" note in docs/DECISIONS.md for the licence posture (these extensions and
 * their API are GPL-3.0 and are NOT vendored in this repo; nothing is hardcoded here).
 */
data class ExtensionPackage(
    val packageName: String,
    /** Class name from the meta-data, e.g. ".AnimePahe". */
    val className: String,
    val label: String,
    val versionName: String,
    val versionCode: Long = 0,
    val isAnime: Boolean,
    val isNsfw: Boolean,
    val sourceDir: String,
) {
    /** Fully-qualified class name: ".AnimePahe" + package -> "eu.kanade...animepahe.AnimePahe". */
    val qualifiedClassName: String
        get() = if (className.startsWith(".")) packageName + className else "$packageName.$className"
}

object ExtensionDiscovery {

    private const val TAG = "ExtensionDiscovery"

    const val FEATURE_ANIME = "tachiyomi.animeextension"
    const val FEATURE_MANGA = "tachiyomi.extension"
    const val META_CLASS = "tachiyomi.animeextension.class"
    const val META_FACTORY = "tachiyomi.animeextension.factory"
    const val META_NSFW = "tachiyomi.animeextension.nsfw"

    /** Every installed package that declares the extension feature and carries the class meta-data. */
    fun discover(context: Context): List<ExtensionPackage> {
        val pm = context.packageManager
        val found = mutableListOf<ExtensionPackage>()

        for (packageName in (extensionPackages(pm, FEATURE_ANIME) + extensionPackages(pm, FEATURE_MANGA)).distinct()) {
            val pkg = runCatching { pm.getPackageInfo(packageName, PackageManager.GET_META_DATA) }
                .getOrNull() ?: continue
            val meta = pkg.applicationInfo?.metaData ?: continue

            val className = meta.getStringOrNull(META_CLASS) ?: meta.getStringOrNull(META_FACTORY)
            if (className.isNullOrBlank()) continue

            val appInfo: ApplicationInfo = pkg.applicationInfo ?: continue
            val vCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pkg.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pkg.versionCode.toLong()
            }
            found += ExtensionPackage(
                packageName = packageName,
                className = className,
                label = runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault(packageName),
                versionName = pkg.versionName.orEmpty(),
                versionCode = vCode,
                isAnime = meta.getBooleanOrDefault(META_CLASS, false) || packageName.contains(".animeextension."),
                isNsfw = meta.getBooleanOrDefault(META_NSFW, false),
                sourceDir = appInfo.sourceDir,
            )
        }
        return found.sortedBy { it.label.lowercase() }
    }

    private fun extensionPackages(pm: PackageManager, feature: String): List<String> =
        runCatching {
            pm.getInstalledPackages(PackageManager.GET_META_DATA)
                .map { it.packageName }
                .filter { name ->
                    // PackageInfo.features is not public API, so match on the known extension
                    // package prefixes; the meta-data check in discover() is the real filter.
                    name.startsWith("eu.kanade.tachiyomi.") ||
                        name.startsWith("com.github.aniyomiorg.") ||
                        name.startsWith("com.github.tachiyomiorg.")
                }
        }.getOrDefault(emptyList())

    private fun android.os.Bundle.getStringOrNull(key: String): String? = runCatching { getString(key) }.getOrNull()

    private fun android.os.Bundle.getBooleanOrDefault(key: String, fallback: Boolean): Boolean =
        runCatching { getBoolean(key, fallback) }.getOrDefault(fallback)

    fun log(found: List<ExtensionPackage>) {
        Log.i(TAG, "Discovered ${found.size} extension package(s)")
        found.forEach { Log.i(TAG, "  ${it.packageName} -> ${it.qualifiedClassName} (${it.label})") }
    }
}