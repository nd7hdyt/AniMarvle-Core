package com.animarvle.app.data.account

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.animarvle.app.R
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.tasks.Task

object GoogleAuthHelper {
    val WEB_CLIENT_ID: String = com.animarvle.app.BuildConfig.GOOGLE_WEB_CLIENT_ID

    fun getGoogleSignInOptions(context: Context): GoogleSignInOptions {
        val serverClientId = try {
            context.getString(R.string.default_web_client_id)
        } catch (_: Exception) {
            WEB_CLIENT_ID
        }

        return GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(serverClientId)
            .requestEmail()
            .requestProfile()
            .build()
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
}
