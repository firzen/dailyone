package com.firzen.dailyone

import org.junit.Assert.*
import org.junit.Test

class JournalSplitTest {
    private val place = GeoPoint(31.2, 121.5, "原地点")
    private val other = GeoPoint(30.0, 120.0, "另一地点")

    @Test fun movesPhotosInOriginalOrderAndPreservesMetadata() {
        val before = Entry(createdAt = 123, title = "标题", body = "正文", photos = listOf("a", "b", "c"),
            videos = listOf("v"), mediaOrder = listOf("b", "v", "a", "c"), etag = "remote", dirty = false,
            photoLocations = mapOf("a" to place, "b" to other))
        val (original, created) = journalSplit(before, setOf("c", "a"), 456)
        assertEquals(listOf("b"), original.photos)
        assertEquals(listOf("b", "v"), original.attachments)
        assertEquals(listOf("a", "c"), created.photos)
        assertEquals(listOf("a", "c"), created.attachments)
        assertEquals(place, original.location)
        assertEquals(place, created.location)
        assertEquals(mapOf("b" to other), original.photoLocations)
        assertEquals(mapOf("a" to place), created.photoLocations)
        assertEquals(before.createdAt, created.createdAt)
        assertEquals(before.notebookId, created.notebookId)
        assertEquals("标题", original.title)
        assertEquals("正文", original.body)
        assertEquals("", created.title)
        assertEquals("", created.body)
        assertTrue(created.videos.isEmpty())
        assertNotEquals(before.id, created.id)
        assertNull(created.etag)
        assertEquals("remote", original.etag)
        assertTrue(original.dirty && created.dirty)
        assertEquals(456L, original.updatedAt)
        assertEquals(456L, created.updatedAt)
    }

    @Test fun explicitPlaceWinsOverPhotoPlace() {
        val entry = Entry(photos = listOf("a", "b"), recordedLocation = other, photoLocations = mapOf("a" to place))
        val (original, created) = journalSplit(entry, setOf("a"))
        assertEquals(other, original.location)
        assertEquals(other, created.location)
    }

    @Test fun allPhotosAllowedWhenTextOrVideoRemains() {
        for (entry in listOf(Entry(body = "正文"), Entry(title = "标题"), Entry(videos = listOf("v")))) {
            val (original, created) = journalSplit(entry.copy(photos = listOf("a")), setOf("a"))
            assertTrue(original.photos.isEmpty())
            assertEquals(listOf("a"), created.photos)
            assertNull(created.location)
        }
    }

    @Test fun rejectsEmptyOriginalEvenIfItHasLocation() {
        assertThrows(IllegalArgumentException::class.java) {
            journalSplit(Entry(photos = listOf("a"), recordedLocation = place), setOf("a"))
        }
    }

    @Test fun rejectsInvalidSelectionAndNonJournalRecords() {
        val entry = Entry(photos = listOf("a", "b"))
        for (selected in listOf(emptySet(), setOf("missing"))) {
            assertThrows(IllegalArgumentException::class.java) { journalSplit(entry, selected) }
        }
        for (invalid in listOf(entry.copy(candidate = true), entry.copy(deleted = true))) {
            assertThrows(IllegalArgumentException::class.java) { journalSplit(invalid, setOf("a")) }
        }
    }
}
