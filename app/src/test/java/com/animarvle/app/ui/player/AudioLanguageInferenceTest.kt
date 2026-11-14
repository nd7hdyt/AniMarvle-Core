package com.animarvle.app.ui.player

import com.animarvle.sourceapi.Audio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioLanguageInferenceTest {

    @Test
    fun testFromRawTag_validLanguages() {
        assertEquals(SpokenLanguage.TURKISH, SpokenLanguage.fromRawTag("Turkish"))
        assertEquals(SpokenLanguage.TURKISH, SpokenLanguage.fromRawTag("tr"))
        assertEquals(SpokenLanguage.TURKISH, SpokenLanguage.fromRawTag("tur"))

        assertEquals(SpokenLanguage.KOREAN, SpokenLanguage.fromRawTag("Korean"))
        assertEquals(SpokenLanguage.KOREAN, SpokenLanguage.fromRawTag("ko"))
        assertEquals(SpokenLanguage.KOREAN, SpokenLanguage.fromRawTag("kor"))

        assertEquals(SpokenLanguage.HINDI, SpokenLanguage.fromRawTag("Hindi"))
        assertEquals(SpokenLanguage.HINDI, SpokenLanguage.fromRawTag("hi"))

        assertEquals(SpokenLanguage.JAPANESE, SpokenLanguage.fromRawTag("Japanese"))
        assertEquals(SpokenLanguage.JAPANESE, SpokenLanguage.fromRawTag("ja"))

        assertEquals(SpokenLanguage.ENGLISH, SpokenLanguage.fromRawTag("English"))
        assertEquals(SpokenLanguage.ENGLISH, SpokenLanguage.fromRawTag("en"))
    }

    @Test
    fun testFromRawTag_genericTagsDoNotHijackLanguages() {
        // "sub", "original", "raw" should not map to Japanese
        assertNotEquals(SpokenLanguage.JAPANESE, SpokenLanguage.fromRawTag("sub"))
        assertNotEquals(SpokenLanguage.JAPANESE, SpokenLanguage.fromRawTag("original"))
        assertNotEquals(SpokenLanguage.JAPANESE, SpokenLanguage.fromRawTag("raw"))

        // "dub" should not map to English
        assertNotEquals(SpokenLanguage.ENGLISH, SpokenLanguage.fromRawTag("dub"))
    }

    @Test
    fun testInferLanguageForUntaggedTrack_respectsActiveMediaLanguage() {
        // Turkish drama
        val turkish = SpokenLanguage.inferLanguageForUntaggedTrack(
            isMovie = false,
            isDonghua = false,
            isAnime = false,
            audioPreference = Audio.SUB,
            activeMediaLanguage = "Turkish"
        )
        assertEquals(SpokenLanguage.TURKISH, turkish)

        // Korean drama
        val korean = SpokenLanguage.inferLanguageForUntaggedTrack(
            isMovie = false,
            isDonghua = false,
            isAnime = false,
            audioPreference = Audio.SUB,
            activeMediaLanguage = "Korean"
        )
        assertEquals(SpokenLanguage.KOREAN, korean)

        // Hindi drama
        val hindi = SpokenLanguage.inferLanguageForUntaggedTrack(
            isMovie = false,
            isDonghua = false,
            isAnime = false,
            audioPreference = Audio.SUB,
            activeMediaLanguage = "hi"
        )
        assertEquals(SpokenLanguage.HINDI, hindi)
    }

    @Test
    fun testInferLanguageForUntaggedTrack_fallbackDefaults() {
        // Anime fallback
        val animeSub = SpokenLanguage.inferLanguageForUntaggedTrack(
            isMovie = false,
            isDonghua = false,
            isAnime = true,
            audioPreference = Audio.SUB,
            activeMediaLanguage = null
        )
        assertEquals(SpokenLanguage.JAPANESE, animeSub)

        // Donghua fallback
        val donghuaSub = SpokenLanguage.inferLanguageForUntaggedTrack(
            isMovie = false,
            isDonghua = true,
            isAnime = false,
            audioPreference = Audio.SUB,
            activeMediaLanguage = null
        )
        assertEquals(SpokenLanguage.CHINESE, donghuaSub)

        // Movie fallback
        val movie = SpokenLanguage.inferLanguageForUntaggedTrack(
            isMovie = true,
            isDonghua = false,
            isAnime = false,
            audioPreference = Audio.SUB,
            activeMediaLanguage = null
        )
        assertEquals(SpokenLanguage.ENGLISH, movie)
    }

    @Test
    fun testIsUntaggedOrUndetermined() {
        assert(SpokenLanguage.isUntaggedOrUndetermined(null))
        assert(SpokenLanguage.isUntaggedOrUndetermined(""))
        assert(SpokenLanguage.isUntaggedOrUndetermined("   "))
        assert(SpokenLanguage.isUntaggedOrUndetermined("und"))
        assert(SpokenLanguage.isUntaggedOrUndetermined("UND"))
        assert(SpokenLanguage.isUntaggedOrUndetermined("undetermined"))
        assert(SpokenLanguage.isUntaggedOrUndetermined("unknown"))
        assert(SpokenLanguage.isUntaggedOrUndetermined("default"))
        assert(SpokenLanguage.isUntaggedOrUndetermined("null"))

        assert(!SpokenLanguage.isUntaggedOrUndetermined("en"))
        assert(!SpokenLanguage.isUntaggedOrUndetermined("ko"))
        assert(!SpokenLanguage.isUntaggedOrUndetermined("tr"))
    }

    @Test
    fun testMultiTrackUntaggedInferenceSimulation() {
        val fallbackLang = SpokenLanguage.KOREAN // from currentServer

        val tracks = listOf(
            null to null, // Track 0: untagged
            null to null, // Track 1: untagged
            "Director's Commentary" to null, // Track 2: custom label, untagged language
            null to "eng" // Track 3: tagged english
        )

        val resolved = mutableListOf<Pair<String, String>>()
        var audioTrackCount = 0

        for ((label, language) in tracks) {
            val detected = (if (!SpokenLanguage.isUntaggedOrUndetermined(label)) SpokenLanguage.fromRawTag(label) else null)
                ?: (if (!SpokenLanguage.isUntaggedOrUndetermined(language)) SpokenLanguage.fromRawTag(language) else null)
                ?: if (audioTrackCount == 0) fallbackLang else null

            val rawCode: String
            val langName: String
            if (detected != null) {
                rawCode = detected.code
                langName = detected.displayName
            } else {
                val trackNum = audioTrackCount + 1
                rawCode = "und_$audioTrackCount"
                langName = label?.takeIf { !SpokenLanguage.isUntaggedOrUndetermined(it) }
                    ?: "Audio Track $trackNum"
            }

            resolved.add(rawCode to langName)
            audioTrackCount++
        }

        // Track 0 gets current server's fallback language (Korean)
        assertEquals("ko" to "Korean", resolved[0])

        // Track 1 does NOT inherit Korean; it becomes Audio Track 2
        assertEquals("und_1" to "Audio Track 2", resolved[1])

        // Track 2 uses its descriptive label
        assertEquals("und_2" to "Director's Commentary", resolved[2])

        // Track 3 detects English
        assertEquals("en" to "English", resolved[3])
    }
}

