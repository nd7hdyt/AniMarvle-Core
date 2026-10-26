package com.animarvle.app.data.sync

import android.content.Context
import android.content.SharedPreferences
import com.animarvle.app.data.account.AccountRepository
import com.animarvle.app.ui.mylist.MyListItem
import com.animarvle.app.ui.mylist.MyListRepository
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class SyncState {
    object Idle : SyncState()
    object Syncing : SyncState()
    data class Success(val itemsSynced: Int, val timestampMs: Long) : SyncState()
    data class Error(val message: String, val isRecoverable: Boolean = false) : SyncState()
}

class GoogleDriveSyncRepository(
    private val context: Context,
    private val myListRepo: MyListRepository,
    private val accountRepo: AccountRepository,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    companion object {
        private const val PREFS_NAME = "google_drive_sync_prefs"
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
        private const val KEY_LAST_SYNC_COUNT = "last_sync_count"

        const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"

        private const val SYNC_FILENAME = "my_list_sync.json"
        private const val DRIVE_FILES_API = "https://www.googleapis.com/drive/v3/files"
        private const val DRIVE_UPLOAD_API = "https://www.googleapis.com/upload/drive/v3/files"

        fun mergeLists(local: List<MyListItem>, remote: List<MyListItem>): List<MyListItem> {
            val map = LinkedHashMap<Long, MyListItem>()

            // 1. Put all remote items
            remote.forEach { map[it.mediaId] = it }

            // 2. Merge local items: if local item was updated more recently, overwrite remote (last-write-wins)
            local.forEach { localItem ->
                val remoteItem = map[localItem.mediaId]
                if (remoteItem == null || localItem.updatedAtMs >= remoteItem.updatedAtMs) {
                    map[localItem.mediaId] = localItem
                }
            }

            // Return descending sorted by last updated
            return map.values.sortedByDescending { it.updatedAtMs }
        }
    }

    fun getLastSyncTime(): Long = prefs.getLong(KEY_LAST_SYNC_TIME, 0L)
    fun getLastSyncCount(): Int = prefs.getInt(KEY_LAST_SYNC_COUNT, 0)

    fun hasDrivePermission(context: Context): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return false
        return GoogleSignIn.hasPermissions(account, Scope(DRIVE_APPDATA_SCOPE))
    }

    suspend fun getAccessToken(context: Context): Result<String> = withContext(Dispatchers.IO) {
        val googleAccount = GoogleSignIn.getLastSignedInAccount(context)
            ?: return@withContext Result.failure(IllegalStateException("Please sign in with Google first"))
        val account = googleAccount.account
            ?: return@withContext Result.failure(IllegalStateException("No Google Account associated"))

        try {
            val token = GoogleAuthUtil.getToken(
                context,
                account,
                "oauth2:$DRIVE_APPDATA_SCOPE"
            )
            Result.success(token)
        } catch (e: UserRecoverableAuthException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sync(): Result<Int> = withContext(Dispatchers.IO) {
        _syncState.value = SyncState.Syncing

        val tokenResult = getAccessToken(context)
        if (tokenResult.isFailure) {
            val err = tokenResult.exceptionOrNull()
            val msg = if (err is UserRecoverableAuthException) {
                "Google Drive permission required. Please allow access."
            } else {
                err?.localizedMessage ?: "Failed to acquire Google Drive auth token"
            }
            val isRecoverable = err is UserRecoverableAuthException
            _syncState.value = SyncState.Error(msg, isRecoverable)
            return@withContext Result.failure(err ?: Exception(msg))
        }

        var token = tokenResult.getOrThrow()

        try {
            // 1. Locate existing sync file in appDataFolder
            val existingFileId = findSyncFile(token)

            // 2. Fetch remote items if file exists
            val remoteItems: List<MyListItem> = if (existingFileId != null) {
                downloadSyncFile(existingFileId, token)
            } else {
                emptyList()
            }

            // 3. Merge local and remote lists
            val localItems = myListRepo.itemsFlow.value
            val mergedList = mergeLists(localItems, remoteItems)

            // 4. Save merged list locally
            myListRepo.saveAll(mergedList)

            // 5. Upload merged list back to Google Drive AppData
            val serialized = myListRepo.serialize(mergedList)
            if (existingFileId != null) {
                updateSyncFile(existingFileId, serialized, token)
            } else {
                createSyncFile(serialized, token)
            }

            // 6. Record success
            val now = System.currentTimeMillis()
            prefs.edit()
                .putLong(KEY_LAST_SYNC_TIME, now)
                .putInt(KEY_LAST_SYNC_COUNT, mergedList.size)
                .apply()

            _syncState.value = SyncState.Success(mergedList.size, now)
            Result.success(mergedList.size)
        } catch (e: Exception) {
            val msg = e.localizedMessage ?: "Sync error occurred"
            _syncState.value = SyncState.Error(msg)
            Result.failure(e)
        }
    }

    private fun findSyncFile(token: String): String? {
        val query = "name = '$SYNC_FILENAME' and trashed = false"
        val url = "$DRIVE_FILES_API?spaces=appDataFolder&q=${java.net.URLEncoder.encode(query, "UTF-8")}&fields=files(id,name,modifiedTime)"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            if (response.code == 403 && errorBody.contains("disabled", ignoreCase = true)) {
                throw IllegalStateException("Google Drive API is disabled. Please enable it in Google Cloud Console.")
            }
            throw IllegalStateException("Google Drive query failed (${response.code}): $errorBody")
        }

        val json = JSONObject(response.body?.string() ?: "{}")
        val files = json.optJSONArray("files") ?: JSONArray()
        if (files.length() > 0) {
            return files.getJSONObject(0).optString("id")
        }
        return null
    }

    private fun downloadSyncFile(fileId: String, token: String): List<MyListItem> {
        val url = "$DRIVE_FILES_API/$fileId?alt=media"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            return emptyList()
        }
        val raw = response.body?.string() ?: return emptyList()
        return myListRepo.deserialize(raw)
    }

    private fun createSyncFile(content: String, token: String) {
        val metadata = JSONObject().apply {
            put("name", SYNC_FILENAME)
            put("parents", JSONArray().put("appDataFolder"))
        }.toString()

        val mediaTypeJson = "application/json; charset=UTF-8".toMediaType()
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addPart(metadata.toRequestBody(mediaTypeJson))
            .addPart(content.toRequestBody(mediaTypeJson))
            .build()

        val request = Request.Builder()
            .url("$DRIVE_UPLOAD_API?uploadType=multipart")
            .addHeader("Authorization", "Bearer $token")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IllegalStateException("Drive file creation failed (${response.code}): ${response.body?.string()}")
        }
    }

    private fun updateSyncFile(fileId: String, content: String, token: String) {
        val mediaTypeJson = "application/json; charset=UTF-8".toMediaType()
        val requestBody = content.toRequestBody(mediaTypeJson)

        val request = Request.Builder()
            .url("$DRIVE_UPLOAD_API/$fileId?uploadType=media")
            .addHeader("Authorization", "Bearer $token")
            .patch(requestBody)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IllegalStateException("Drive file update failed (${response.code}): ${response.body?.string()}")
        }
    }
}
