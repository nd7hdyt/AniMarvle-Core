package com.animarvle.app.extensions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log

/**
 * Handles package install callbacks from [PackageInstaller.Session].
 *
 * Launches the system install confirmation dialog when action is required,
 * without ever showing a launcher/open prompt for the headless extension package.
 */
class ExtensionInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: "extension"

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirmIntent?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(it)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Extension installed successfully: $packageName")
                onPackageInstalled?.invoke(packageName)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Log.d(TAG, "Extension installation cancelled by user: $packageName")
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(TAG, "Extension install status $status: $message")
            }
        }
    }

    companion object {
        private const val TAG = "InstallReceiver"
        const val ACTION_INSTALL_COMPLETE = "com.animarvle.app.ACTION_INSTALL_COMPLETE"
        const val EXTRA_PACKAGE_NAME = "com.animarvle.app.EXTRA_PACKAGE_NAME"

        @Volatile
        var onPackageInstalled: ((String) -> Unit)? = null
    }
}
