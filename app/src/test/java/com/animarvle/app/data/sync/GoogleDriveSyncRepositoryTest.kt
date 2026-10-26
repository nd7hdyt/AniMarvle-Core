package com.animarvle.app.data.sync

import com.animarvle.app.ui.mylist.MyListItem
import com.animarvle.app.ui.mylist.MyListStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleDriveSyncRepositoryTest {

    private fun createItem(
        mediaId: Long,
        title: String,
        status: MyListStatus,
        updatedAtMs: Long
    ): MyListItem {
        return MyListItem(
            mediaId = mediaId,
            title = title,
            posterUrl = "https://example.com/poster/$mediaId.jpg",
            format = "TV",
            score = 8.5,
            status = status,
            progress = 1,
            totalEpisodes = 12,
            updatedAtMs = updatedAtMs,
            year = 2026
        )
    }

    @Test
    fun testDisjointListsMerge() {
        val local = listOf(
            createItem(1L, "One Piece", MyListStatus.WATCHING, 1000L)
        )
        val remote = listOf(
            createItem(2L, "Bleach", MyListStatus.COMPLETED, 2000L)
        )

        val merged = GoogleDriveSyncRepository.mergeLists(local, remote)

        assertEquals(2, merged.size)
        assertEquals(2L, merged[0].mediaId) // Bleach (t=2000)
        assertEquals(1L, merged[1].mediaId) // One Piece (t=1000)
    }

    @Test
    fun testConflictResolutionLocalNewerWins() {
        val local = listOf(
            createItem(1L, "Solo Leveling", MyListStatus.WATCHING, 5000L)
        )
        val remote = listOf(
            createItem(1L, "Solo Leveling", MyListStatus.PLAN_TO_WATCH, 3000L)
        )

        val merged = GoogleDriveSyncRepository.mergeLists(local, remote)

        assertEquals(1, merged.size)
        assertEquals(MyListStatus.WATCHING, merged[0].status)
        assertEquals(5000L, merged[0].updatedAtMs)
    }

    @Test
    fun testConflictResolutionRemoteNewerWins() {
        val local = listOf(
            createItem(1L, "Frieren", MyListStatus.PLAN_TO_WATCH, 1000L)
        )
        val remote = listOf(
            createItem(1L, "Frieren", MyListStatus.COMPLETED, 9000L)
        )

        val merged = GoogleDriveSyncRepository.mergeLists(local, remote)

        assertEquals(1, merged.size)
        assertEquals(MyListStatus.COMPLETED, merged[0].status)
        assertEquals(9000L, merged[0].updatedAtMs)
    }

    @Test
    fun testEqualTimestampsResolvesDeterministically() {
        val local = listOf(
            createItem(1L, "Overgeared", MyListStatus.WATCHING, 4000L)
        )
        val remote = listOf(
            createItem(1L, "Overgeared", MyListStatus.PLAN_TO_WATCH, 4000L)
        )

        val merged = GoogleDriveSyncRepository.mergeLists(local, remote)

        assertEquals(1, merged.size)
        assertEquals(MyListStatus.WATCHING, merged[0].status)
    }

    @Test
    fun testEmptyListsHandling() {
        assertTrue(GoogleDriveSyncRepository.mergeLists(emptyList(), emptyList()).isEmpty())

        val local = listOf(createItem(1L, "Naruto", MyListStatus.COMPLETED, 2000L))
        assertEquals(1, GoogleDriveSyncRepository.mergeLists(local, emptyList()).size)
        assertEquals(1, GoogleDriveSyncRepository.mergeLists(emptyList(), local).size)
    }

    @Test
    fun testMergedListSortedDescendingByUpdatedAt() {
        val local = listOf(
            createItem(1L, "Anime 1", MyListStatus.WATCHING, 100L),
            createItem(2L, "Anime 2", MyListStatus.WATCHING, 400L)
        )
        val remote = listOf(
            createItem(3L, "Anime 3", MyListStatus.WATCHING, 300L),
            createItem(4L, "Anime 4", MyListStatus.WATCHING, 200L)
        )

        val merged = GoogleDriveSyncRepository.mergeLists(local, remote)

        assertEquals(listOf(2L, 3L, 4L, 1L), merged.map { it.mediaId })
    }
}
