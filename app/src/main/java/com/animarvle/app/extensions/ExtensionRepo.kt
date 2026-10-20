package com.animarvle.app.extensions

data class ExtensionRepo(
    val baseUrl: String,
    val name: String,
    val shortName: String? = null,
    val website: String = "",
    val signingKeyFingerprint: String = "",
)
