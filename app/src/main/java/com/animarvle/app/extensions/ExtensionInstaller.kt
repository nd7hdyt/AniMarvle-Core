package com.animarvle.app.extensions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

class ExtensionInstaller(private val context: Context) {

    private val client = OkHttpClient()

    sealed class InstallResult {
        object SuccessLaunchingInstaller : InstallResult()
        object PermissionRequired : InstallResult()
        data class Error(val message: String) : InstallResult()
    }

    suspend fun downloadAndInstallApk(
        apkUrl: String,
        packageName: String,
        onProgress: (Int) -> Unit = {},
    ): InstallResult = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    return@withContext InstallResult.PermissionRequired
                }
            }

            val cacheDir = File(context.cacheDir, "extension_apks").apply { mkdirs() }
            val apkFile = File(cacheDir, "$packageName.apk")

            val request = Request.Builder().url(apkUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext InstallResult.Error("HTTP ${response.code}: Failed to download APK")
                }

                val body = response.body ?: return@withContext InstallResult.Error("Empty download body")
                val totalLength = body.contentLength()
                var downloaded: Long = 0

                body.byteStream().use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead
                            if (totalLength > 0) {
                                val progress = ((downloaded * 100) / totalLength).toInt()
                                onProgress(progress)
                            }
                        }
                        output.flush()
                    }
                }
            }

            installPackage(apkFile, packageName)
        } catch (e: Exception) {
            InstallResult.Error(e.message ?: "Failed to install extension")
        }
    }

    private fun installPackage(apkFile: File, packageName: String): InstallResult {
        return try {
            installPackageViaSession(apkFile, packageName)
            InstallResult.SuccessLaunchingInstaller
        } catch (e: Exception) {
            android.util.Log.w("ExtensionInstaller", "PackageInstaller session failed, using intent fallback", e)
            installPackageViaIntent(apkFile)
        }
    }

    private fun installPackageViaSession(apkFile: File, packageName: String) {
        val packageInstaller = context.packageManager.packageInstaller
        val params = android.content.pm.PackageInstaller.SessionParams(
            android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        ).apply {
            setAppPackageName(packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(android.content.pm.PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
        }

        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)

        apkFile.inputStream().use { input ->
            session.openWrite("package", 0, apkFile.length()).use { output ->
                input.copyTo(output)
                session.fsync(output)
            }
        }

        val intent = Intent(context, ExtensionInstallReceiver::class.java).apply {
            action = ExtensionInstallReceiver.ACTION_INSTALL_COMPLETE
            putExtra(ExtensionInstallReceiver.EXTRA_PACKAGE_NAME, packageName)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
        } else {
            android.app.PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = android.app.PendingIntent.getBroadcast(context, sessionId, intent, flags)
        session.commit(pendingIntent.intentSender)
        session.close()
    }

    private fun installPackageViaIntent(apkFile: File): InstallResult {
        val apkUri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            apkFile,
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, context.packageName)
        }
        context.startActivity(installIntent)
        return InstallResult.SuccessLaunchingInstaller
    }

    fun openUnknownSourcesSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    fun requestUninstall(packageName: String) {
        val uninstallIntent = Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
            data = Uri.parse("package:$packageName")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
        context.startActivity(uninstallIntent)
    }
}
