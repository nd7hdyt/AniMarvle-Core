package com.animarvle.sourceapi

/** Kinds of playable content (ARCH §4). TV series are not in v1. */
enum class MediaType { ANIME, MOVIE }

/** Audio track kinds. RAW = original audio only, typical for movies (ARCH §6). */
enum class Audio { SUB, DUB, RAW }