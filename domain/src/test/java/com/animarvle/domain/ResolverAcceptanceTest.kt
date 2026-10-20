package com.animarvle.domain

import com.animarvle.sourceapi.Audio
import com.animarvle.sourceapi.MediaType
import com.animarvle.sourceapi.SourceEpisode
import com.animarvle.sourceapi.SourceMedia
import com.animarvle.sourceapi.Stream
import com.animarvle.sourceapi.StreamKind
import com.animarvle.sourceapi.Titles
import com.animarvle.sourcesample.FakeSource
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resolver acceptance tests, PROMPT §10 "Resolver acceptance tests (M3, with FakeSource)".
 * Each @Test name matches the numbered requirement it proves.
 */
class ResolverAcceptanceTest {

    private val resolver = StreamResolver(timeoutMs = 2_000L)

    private suspend fun episodesOf(source: FakeSource, title: String, type: MediaType): List<SourceEpisode> {
        val media = source.search(title, type).first()
        return source.episodes(media)
    }

    // 1. Source declares SUB+DUB -> both tabs enabled; SUB only -> Dub disabled + message.
    @Test
    fun `1 sub and dub`() = runBlocking {
        val both = FakeSource()
        val bothEp = episodesOf(both, "Death Note", MediaType.ANIME).first()
        val bothTabs = DerivedUi.audioTabs(both.options(bothEp))
        assertTrue("SUB tab must be enabled", bothTabs.subEnabled)
        assertTrue("DUB tab must be enabled", bothTabs.dubEnabled)
        assertFalse("control must be visible", bothTabs.hidden)

        val subOnly = FakeSource().apply { audioKeys = setOf(Audio.SUB) }
        val subEp = episodesOf(subOnly, "Death Note", MediaType.ANIME).first()
        val subTabs = DerivedUi.audioTabs(subOnly.options(subEp))
        assertTrue(subTabs.subEnabled)
        assertFalse("Dub tab must be disabled", subTabs.dubEnabled)
        assertFalse(subTabs.hidden)
        assertNotNull("a hint must explain why", subTabs.unavailableHint)
    }

    // 2. Server A fails, B succeeds -> user sees B playing; chip B selected.
    @Test
    fun `2 server fallback`() = runBlocking {
        val source = FakeSource()
        val ep = episodesOf(source, "Death Note", MediaType.ANIME).first()
        source.failingServers[ep.url] = setOf("mirror-a")

        val events = resolver.resolve(
            source, ep, PlaybackSelection(Audio.SUB), lastUsedServer = "mirror-a",
        ).toList()

        val fallback = events.filterIsInstance<ResolveEvent.Fallback>().single()
        assertEquals("mirror-a", fallback.from)
        assertEquals("mirror-b", fallback.to)
        assertTrue("error cause must be reported", fallback.cause.isNotBlank())

        val resolved = events.filterIsInstance<ResolveEvent.Resolved>().single()
        assertEquals("mirror-b", resolved.serverKey)
        assertTrue(resolved.media.streams.isNotEmpty())
    }

    // 3. All servers fail -> "all servers unavailable" with Retry.
    @Test
    fun `3 all servers fail`() = runBlocking {
        val source = FakeSource()
        val ep = episodesOf(source, "Death Note", MediaType.ANIME).first()
        source.failingServers[ep.url] = setOf("mirror-a", "mirror-b", "mirror-c")

        val events = resolver.resolve(source, ep, PlaybackSelection(Audio.SUB)).toList()
        val failed = events.filterIsInstance<ResolveEvent.AllServersFailed>().single()
        assertEquals(listOf("mirror-a", "mirror-b", "mirror-c"), failed.tried)
        assertTrue(events.none { it is ResolveEvent.Resolved })
    }

    // 4. HLS master with 360/720/1080 -> quality sheet shows Auto + 3 levels.
    @Test
    fun `4 hls master quality sheet`() = runBlocking {
        val source = FakeSource().apply { adaptiveMaster = true }
        val ep = episodesOf(source, "Death Note", MediaType.ANIME).first()
        val resolved = resolver.resolve(source, ep, PlaybackSelection(Audio.SUB)).toList()
            .filterIsInstance<ResolveEvent.Resolved>().single()

        assertEquals(StreamKind.HLS, resolved.media.streams.single().kind)
        // The player reports the levels carried by the master.
        val fromPlayer = listOf(360, 720, 1080).map { Stream("m3u8/$it", StreamKind.HLS, height = it) }
        val sheet = DerivedUi.qualitySheet(resolved.media, fromPlayer)!!
        assertEquals(4, sheet.size)
        assertEquals(DerivedUi.AUTO_ID, sheet.first().id)
        assertEquals("1080p", sheet[1].label)
        assertEquals("720p", sheet[2].label)
        assertEquals("360p", sheet[3].label)
    }

    // 5. Direct (non-HLS) multi-quality list -> same sheet.
    @Test
    fun `5 direct streams quality sheet`() = runBlocking {
        val source = FakeSource()
        val ep = episodesOf(source, "Death Note", MediaType.ANIME).first()
        val resolved = resolver.resolve(source, ep, PlaybackSelection(Audio.SUB)).toList()
            .filterIsInstance<ResolveEvent.Resolved>().single()

        val sheet = DerivedUi.qualitySheet(resolved.media)!!
        assertEquals(4, sheet.size)
        assertEquals(DerivedUi.AUTO_ID, sheet.first().id)
        assertEquals(listOf("1080p", "720p", "360p"), sheet.drop(1).map { it.label })
        assertTrue("concrete levels must carry their stream", sheet.drop(1).all { it.stream != null })
    }

    // 6. Source returns skip-times -> Skip buttons appear.
    @Test
    fun `6 skip times`() = runBlocking {
        val source = FakeSource()
        val ep = episodesOf(source, "Death Note", MediaType.ANIME).first()
        val resolved = resolver.resolve(source, ep, PlaybackSelection(Audio.SUB)).toList()
            .filterIsInstance<ResolveEvent.Resolved>().single()

        val skip = resolved.media.skipTimes!!
        assertNotNull("Skip Intro needs an opening range", skip.opening)
        assertNotNull("Skip Outro needs an ending range", skip.ending)
        assertTrue(45_000L in skip.opening!!)
    }

    // 7. Source returns subtitle tracks -> selectable; default language honored.
    @Test
    fun `7 subtitle tracks`() = runBlocking {
        val source = FakeSource()
        val ep = episodesOf(source, "Death Note", MediaType.ANIME).first()
        val resolved = resolver.resolve(source, ep, PlaybackSelection(Audio.SUB)).toList()
            .filterIsInstance<ResolveEvent.Resolved>().single()

        assertEquals(2, resolved.media.subtitles.size)
        assertEquals("en", DerivedUi.pickSubtitle(resolved.media.subtitles, "en")?.language)
        assertEquals("es", DerivedUi.pickSubtitle(resolved.media.subtitles, "es-419")?.language)
        assertNull("unknown language picks nothing", DerivedUi.pickSubtitle(resolved.media.subtitles, "fr"))
    }

    // 8. Mid-playback server failure -> fallback resuming at last position.
    @Test
    fun `8 mid playback fallback keeps position`() = runBlocking {
        val source = FakeSource()
        val ep = episodesOf(source, "Death Note", MediaType.ANIME).first()
        source.failingServers[ep.url] = setOf("mirror-b")

        val events = resolver.resolve(
            source,
            ep,
            PlaybackSelection(Audio.SUB),
            startAfterServer = "mirror-a",
            resumedAtMs = 123_456L,
        ).toList()

        val resolved = events.filterIsInstance<ResolveEvent.Resolved>().single()
        assertEquals("must fall through to mirror-c", "mirror-c", resolved.serverKey)
        assertEquals("resume position must be preserved", 123_456L, resolved.resumedAtMs)
    }

    // 9. Metadata item not found in a source -> "no match"; manual pick still possible.
    @Test
    fun `9 no match falls back to manual pick`() = runBlocking {
        val source = FakeSource()
        val meta = MetaQuery(
            metaId = "anilist:999999",
            type = MediaType.ANIME,
            titles = Titles(english = "A Show That Does Not Exist Here"),
            year = 2024,
        )
        val matcher = SourceMatcher()
        assertEquals(MatchResult.NoMatch, matcher.match(meta, listOf(source)))

        // Manual pick: the user searches the chosen source directly (ARCH §7.1 step 4).
        val manual = source.search("Naruto", MediaType.ANIME)
        assertTrue("manual search must still return results", manual.isNotEmpty())
    }

    // 10. Movie: one SourceEpisode(number = 1); same pipeline; resume restores; >=90 % watched.
    @Test
    fun `10 movie single episode and watched rule`() = runBlocking {
        val source = FakeSource().apply { audioKeys = setOf(Audio.RAW) }
        val movie: SourceMedia = source.search("Big Buck Bunny", MediaType.MOVIE).single()

        val episodes = source.episodes(movie)
        assertEquals("a movie is exactly one playable unit", 1, episodes.size)
        assertEquals(1f, episodes.single().number, 0.001f)

        val events = resolver.resolve(
            source, episodes.single(), PlaybackSelection(Audio.RAW), resumedAtMs = 61_000L,
        ).toList()
        val resolved = events.filterIsInstance<ResolveEvent.Resolved>().single()
        assertEquals(61_000L, resolved.resumedAtMs)

        assertTrue(DerivedUi.isWatched(90_000L, 100_000L))
        assertTrue(DerivedUi.isWatched(95_000L, 100_000L))
        assertFalse(DerivedUi.isWatched(89_000L, 100_000L))
    }

    // 11. Movie, RAW-only audio: Sub|Dub hidden; quality + server fallback still work.
    @Test
    fun `11 movie raw only`() = runBlocking {
        val source = FakeSource().apply { audioKeys = setOf(Audio.RAW) }
        val movie = source.search("Big Buck Bunny", MediaType.MOVIE).single()
        val episode = source.episodes(movie).single()

        val tabs = DerivedUi.audioTabs(source.options(episode))
        assertTrue("Sub|Dub control must be hidden, not disabled", tabs.hidden)

        source.failingServers[episode.url] = setOf("mirror-a")
        val events = resolver.resolve(source, episode, PlaybackSelection(Audio.RAW)).toList()
        assertTrue(events.any { it is ResolveEvent.Fallback })
        val resolved = events.filterIsInstance<ResolveEvent.Resolved>().single()
        assertEquals("mirror-b", resolved.serverKey)

        val sheet = DerivedUi.qualitySheet(resolved.media)
        assertNotNull("quality sheet must still be available", sheet)
        assertEquals(4, sheet!!.size)
    }

    // 12. Matcher, movies: same title, different years -> the year-matching candidate wins.
    @Test
    fun `12 movie matcher separates remakes by year`() = runBlocking {
        val source = FakeSource()
        val matcher = SourceMatcher()

        val meta2012 = MetaQuery(
            metaId = "tmdb:movie:2001",
            type = MediaType.MOVIE,
            titles = Titles(english = "Tears of Steel"),
            year = 2012,
        )
        val match2012 = matcher.match(meta2012, listOf(source))
        assertTrue(match2012 is MatchResult.Matched)
        assertEquals(2012, (match2012 as MatchResult.Matched).media.year)

        val meta2009 = meta2012.copy(year = 2009, metaId = "tmdb:movie:2000")
        val match2009 = matcher.match(meta2009, listOf(source))
        assertTrue(match2009 is MatchResult.Matched)
        assertEquals(2009, (match2009 as MatchResult.Matched).media.year)
    }

    // 13. Matcher recognizes (Dub) catalogue variants when Audio.DUB is requested
    @Test
    fun `13 matcher selects dub variant when audio is dub`() = runBlocking {
        val dubMedia = SourceMedia(
            url = "https://example.com/one-piece-dub",
            title = "One Piece (Dub)",
            year = 1999,
            episodeCount = 1000,
            poster = null,
            type = MediaType.ANIME,
        )
        val subMedia = SourceMedia(
            url = "https://example.com/one-piece",
            title = "One Piece",
            year = 1999,
            episodeCount = 1000,
            poster = null,
            type = MediaType.ANIME,
        )

        val source = FakeSource().apply {
            // Provide both sub and dub search results
            val customSearch = listOf(subMedia, dubMedia)
            // FakeSource search implementation
        }

        val matcher = SourceMatcher()
        val dubQuery = MetaQuery(
            metaId = "anilist:21",
            type = MediaType.ANIME,
            titles = Titles(english = "One Piece"),
            requestedAudio = Audio.DUB,
        )
        val subQuery = MetaQuery(
            metaId = "anilist:21",
            type = MediaType.ANIME,
            titles = Titles(english = "One Piece"),
            requestedAudio = Audio.SUB,
        )

        val dubScore = matcher.score(dubQuery, dubMedia)
        val subScoreOnDubMedia = matcher.score(subQuery, dubMedia)
        val dubScoreOnSubMedia = matcher.score(dubQuery, subMedia)
        val subScoreOnSubMedia = matcher.score(subQuery, subMedia)

        assertNotNull(dubScore)
        assertNotNull(subScoreOnDubMedia)
        assertNotNull(dubScoreOnSubMedia)
        assertNotNull(subScoreOnSubMedia)

        // When DUB requested, dubMedia score must exceed subMedia score
        assertTrue(dubScore!!.score > dubScoreOnSubMedia!!.score)
        // When SUB requested, subMedia score must exceed dubMedia score
        assertTrue(subScoreOnSubMedia!!.score > subScoreOnDubMedia!!.score)
    }
}