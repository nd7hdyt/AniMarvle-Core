package com.animarvle.app.data.account

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.UUID

class AccountRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _accountFlow = MutableStateFlow<UserAccount?>(loadAccount())
    val accountFlow: StateFlow<UserAccount?> = _accountFlow.asStateFlow()

    val currentAccount: UserAccount?
        get() = _accountFlow.value

    fun isOnboardingCompleted(): Boolean {
        return prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
    }

    fun setOnboardingCompleted(completed: Boolean) {
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, completed).apply()
    }

    private fun loadAccount(): UserAccount? {
        val json = prefs.getString(KEY_USER_ACCOUNT, null) ?: return null
        return UserAccount.fromJson(json)
    }

    private fun saveAccount(account: UserAccount?) {
        val editor = prefs.edit()
        if (account != null) {
            editor.putString(KEY_USER_ACCOUNT, account.toJson())
        } else {
            editor.remove(KEY_USER_ACCOUNT)
        }
        editor.apply()
        _accountFlow.value = account
    }

    suspend fun signInWithGoogle(
        name: String = "Google User",
        email: String = "user@gmail.com",
    ): Result<UserAccount> = withContext(Dispatchers.IO) {
        // Realistic simulated authentication delay matching Image 4 "Signing you in..."
        delay(1200)
        val account = UserAccount(
            id = "google_" + UUID.randomUUID().toString().take(8),
            username = name,
            email = email,
            provider = "Google",
            isLoggedIn = true,
            syncedItemsCount = currentAccount?.syncedItemsCount ?: 0,
        )
        saveAccount(account)
        setOnboardingCompleted(true)
        Result.success(account)
    }

    suspend fun signInWithPassword(
        email: String,
        password: String,
    ): Result<UserAccount> = withContext(Dispatchers.IO) {
        delay(1000)
        if (email.isBlank() || !email.contains("@")) {
            return@withContext Result.failure(IllegalArgumentException("Please enter a valid email address"))
        }
        if (password.length < 4) {
            return@withContext Result.failure(IllegalArgumentException("Password must be at least 4 characters"))
        }
        val defaultUsername = email.substringBefore("@").replaceFirstChar { it.uppercase() }
        val account = UserAccount(
            id = "pwd_" + UUID.randomUUID().toString().take(8),
            username = defaultUsername,
            email = email,
            provider = "Animarvle Account",
            isLoggedIn = true,
            syncedItemsCount = currentAccount?.syncedItemsCount ?: 0,
        )
        saveAccount(account)
        setOnboardingCompleted(true)
        Result.success(account)
    }

    suspend fun signUp(
        username: String,
        email: String,
        password: String,
    ): Result<UserAccount> = withContext(Dispatchers.IO) {
        delay(1000)
        if (username.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Username cannot be empty"))
        }
        if (email.isBlank() || !email.contains("@")) {
            return@withContext Result.failure(IllegalArgumentException("Please enter a valid email address"))
        }
        if (password.length < 6) {
            return@withContext Result.failure(IllegalArgumentException("Password must be at least 6 characters"))
        }
        val account = UserAccount(
            id = "user_" + UUID.randomUUID().toString().take(8),
            username = username,
            email = email,
            provider = "Animarvle Account",
            isLoggedIn = true,
            syncedItemsCount = 0,
        )
        saveAccount(account)
        setOnboardingCompleted(true)
        Result.success(account)
    }

    fun onGoogleSignInSuccess(googleAccount: com.google.android.gms.auth.api.signin.GoogleSignInAccount): UserAccount {
        val displayName = googleAccount.displayName?.takeIf { it.isNotBlank() }
            ?: googleAccount.givenName?.takeIf { it.isNotBlank() }
            ?: googleAccount.email?.substringBefore("@")
            ?: "Google User"

        val account = UserAccount(
            id = googleAccount.id ?: ("google_" + UUID.randomUUID().toString().take(8)),
            username = displayName,
            email = googleAccount.email ?: "google.user@gmail.com",
            avatarUrl = googleAccount.photoUrl?.toString(),
            provider = "Google",
            isLoggedIn = true,
            syncedItemsCount = currentAccount?.syncedItemsCount ?: 0,
        )
        saveAccount(account)
        setOnboardingCompleted(true)
        return account
    }

    fun updateProfile(newUsername: String, newAvatarUrl: String?): Boolean {
        val current = currentAccount ?: return false
        val updated = current.copy(
            username = newUsername.trim().ifEmpty { current.username },
            avatarUrl = if (!newAvatarUrl.isNullOrBlank()) newAvatarUrl else current.avatarUrl,
        )
        saveAccount(updated)
        return true
    }

    fun signOut(context: Context? = null) {
        saveAccount(null)
        if (context != null) {
            try {
                GoogleAuthHelper.signOut(context)
            } catch (_: Exception) {}
        }
    }

    companion object {
        private const val PREFS_NAME = "animarvle_account"
        private const val KEY_USER_ACCOUNT = "user_account_data"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
    }
}
