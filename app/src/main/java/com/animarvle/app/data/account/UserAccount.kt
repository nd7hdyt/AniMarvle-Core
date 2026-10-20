package com.animarvle.app.data.account

import org.json.JSONObject

data class UserAccount(
    val id: String,
    val username: String,
    val email: String,
    val avatarUrl: String? = null,
    val provider: String = "Email",
    val isLoggedIn: Boolean = true,
    val joinedAt: Long = System.currentTimeMillis(),
    val syncedItemsCount: Int = 0,
) {
    fun toJson(): String {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("username", username)
        obj.put("email", email)
        obj.put("avatarUrl", avatarUrl ?: "")
        obj.put("provider", provider)
        obj.put("isLoggedIn", isLoggedIn)
        obj.put("joinedAt", joinedAt)
        obj.put("syncedItemsCount", syncedItemsCount)
        return obj.toString()
    }

    companion object {
        fun fromJson(json: String): UserAccount? {
            return try {
                val obj = JSONObject(json)
                UserAccount(
                    id = obj.getString("id"),
                    username = obj.getString("username"),
                    email = obj.getString("email"),
                    avatarUrl = obj.optString("avatarUrl").ifEmpty { null },
                    provider = obj.optString("provider", "Email"),
                    isLoggedIn = obj.optBoolean("isLoggedIn", true),
                    joinedAt = obj.optLong("joinedAt", System.currentTimeMillis()),
                    syncedItemsCount = obj.optInt("syncedItemsCount", 0),
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
