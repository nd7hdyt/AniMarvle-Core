package eu.kanade.tachiyomi.util.system

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Minimal subset of Aniyomi's DeviceUtil, vendored for [WebViewInterceptor].
 * Only the flags the WebView interceptor actually branches on are provided.
 */
object DeviceUtil {

    val isMiui: Boolean
        get() = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) &&
            systemProperty("ro.miui.ui.version.name") != null

    val isSamsung: Boolean
        get() = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    private fun systemProperty(key: String): String? = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        get.invoke(null, key) as? String
    }.getOrNull()
}

/** Plain-text toast; the i18n module is not vendored. */
fun Context.toast(message: String, duration: Int = android.widget.Toast.LENGTH_SHORT) {
    android.widget.Toast.makeText(this, message, duration).show()
}