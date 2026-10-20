package com.animarvle.app.extensions

import android.content.Context
import android.util.Log
import dalvik.system.DexClassLoader
import java.io.File

/**
 * Loads an installed Aniyomi/Mihon extension's classes.
 *
 * ## Why DexClassLoader and not createPackageContext
 * Device-verified on SM_A546S / Android 16 (AnimePahe 14.45): `createPackageContext(pkg, 0)
 * .classLoader` returns a loader with an **empty dexElements array**
 * (`DexPathList[[], nativeLibraryDirectories=[...]]`), so `loadClass` throws
 * ClassNotFoundException even though the installed APK plainly contains `classes.dex`
 * (pulled and verified: 124,504 bytes). These extension packages are never launched
 * (`notLaunched=true`), and ART never wires their dex into a package-context classloader.
 *
 * Mihon/Aniyomi sidestep this the same way we do: load the extension APK's dex directly with
 * [DexClassLoader], using our own classloader as parent so the dex binds to the
 * `:aniyomi-api` classes we vendored. We read the APK from the OS-managed install path
 * (`ApplicationInfo.sourceDir`), which is readable and not writable by other apps, so this
 * stays within Android's dynamic-code-loading rules (PROMPT §3.3 — nothing is vendored, the
 * user installs the extensions).
 */
object TachiyomiExtensionLoader {

    private const val TAG = "ExtensionLoader"

    sealed interface LoadResult {
        data class Success(val instance: Any, val loader: ClassLoader) : LoadResult
        data class Failure(val reason: String, val cause: Throwable? = null) : LoadResult
    }

    fun load(context: Context, pkg: ExtensionPackage): LoadResult {
        val appClassLoader = context.classLoader ?: return LoadResult.Failure("no app classloader")
        return try {
            val loader = classLoaderFor(context, pkg, appClassLoader)
                ?: return LoadResult.Failure("could not open APK for ${pkg.packageName}")

            val clazz = try {
                loader.loadClass(pkg.qualifiedClassName)
            } catch (e: NoClassDefFoundError) {
                return LoadResult.Failure(
                    "API classes missing (eu.kanade.tachiyomi.animesource.*) - the host needs " +
                        "the :aniyomi-api module on its classpath",
                    e,
                )
            }

            val instance = clazz.getDeclaredConstructor().newInstance()

            Log.i(TAG, "Loaded ${pkg.packageName} -> ${instance.javaClass.name}")
            LoadResult.Success(instance, loader)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load ${pkg.packageName}", t)
            LoadResult.Failure(t.message ?: t::class.java.simpleName, t)
        }
    }

    /** Builds a DexClassLoader over the installed APK (base + any split dex), parented to us. */
    private fun classLoaderFor(
        context: Context,
        pkg: ExtensionPackage,
        parent: ClassLoader,
    ): ClassLoader? {
        val pm = context.packageManager
        val info = pm.getApplicationInfo(pkg.packageName, 0)
        val dexDir = optimizedDir(context)
        // DexClassLoader takes one apk path; extensions ship as a single base.apk, so use
        // sourceDir. splitSourceDirs is logged if it ever becomes non-empty.
        info.splitSourceDirs?.takeIf { it.isNotEmpty() }?.let {
            Log.w(TAG, "${pkg.packageName} has splits: $it (using base.apk only)")
        }
        val apk = File(info.sourceDir)
        if (!apk.isFile) return null
        return DexClassLoader(apk.absolutePath, dexDir.absolutePath, null, parent)
    }

    /** Best-effort check for whether the Aniyomi API classes are present on our classpath. */
    fun apiClassesPresent(): Boolean = try {
        Class.forName("eu.kanade.tachiyomi.animesource.model.SAnime")
        true
    } catch (t: Throwable) {
        false
    }

    /** Scratch dir for the dex optimiser (must be writable; must not be app-writable by others). */
    fun optimizedDir(context: Context): File =
        File(context.codeCacheDir, "extension-dex").apply { mkdirs() }
}