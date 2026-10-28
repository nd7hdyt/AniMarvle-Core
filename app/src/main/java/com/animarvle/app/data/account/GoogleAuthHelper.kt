package com.animarvle.app.data.account

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.animarvle.app.R
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.animarvle.app.data.sync.GoogleDriveSyncRepository
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task

object GoogleAuthHelper {
    val WEB_CLIENT_ID: String = com.animarvle.app.BuildConfig.GOOGLE_WEB_CLIENT_ID

    fun getGoogleSignInOptions(context: Context): GoogleSignInOptions {
        val serverClientId = try {
            val fromRes = context.getString(R.string.default_web_client_id)
            if (fromRes.isNotBlank()) fromRes else WEB_CLIENT_ID
        } catch (_: Exception) {
            WEB_CLIENT_ID
        }.trim()

        val builder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            // Request drive.appdata scope at sign-in so the OAuth consent screen covers
            // Google Drive sync without requiring a separate permission grant later.
            .requestScopes(Scope(GoogleDriveSyncRepository.DRIVE_APPDATA_SCOPE))

        if (serverClientId.isNotEmpty()) {
            builder.requestIdToken(serverClientId)
        }

        return builder.build()
    }

    fun getClient(activity: Activity): GoogleSignInClient {
        return GoogleSignIn.getClient(activity, getGoogleSignInOptions(activity))
    }

    fun getClient(context: Context): GoogleSignInClient {
        return GoogleSignIn.getClient(context, getGoogleSignInOptions(context))
    }

    fun getSignInIntent(activity: Activity): Intent {
        return getClient(activity).signInIntent
    }

    fun getSignedInAccountFromIntent(data: Intent?): Task<GoogleSignInAccount> {
        return GoogleSignIn.getSignedInAccountFromIntent(data)
    }

    fun signOut(context: Context, onComplete: () -> Unit = {}) {
        getClient(context).signOut().addOnCompleteListener {
            onComplete()
        }
    }

    fun getSigningSha1(context: Context): String {
        return try {
            val packageInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.GET_SIGNATURES
                )
            }
            val signatures = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }
            val cert = signatures?.firstOrNull()?.toByteArray() ?: return "Unknown"
            val md = java.security.MessageDigest.getInstance("SHA-1")
            val digest = md.digest(cert)
            digest.joinToString(":") { "%02X".format(it) }
        } catch (e: Exception) {
            "Unavailable: ${e.message}"
        }
    }
}
