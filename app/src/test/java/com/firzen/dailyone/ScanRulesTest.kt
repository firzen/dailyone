package com.firzen.dailyone

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class ScanRulesTest {
    private fun photo() = "${UUID.randomUUID()}.jpg"
    @Test fun dayGroupingRespectsMidnightInLocalTimezone() {
        val time = Instant.parse("2024-01-01T18:00:00Z").toEpochMilli()
        assertEquals("2024-01-02", scanDay(time, ZoneId.of("Asia/Shanghai")))
        assertEquals("2024-01-01", scanDay(time, ZoneId.of("UTC")))
    }
    @Test fun candidatesAndTheirDeletionsNeverSync() {
        val entry = Entry(candidate = true, scanDay = "2024-01-01", photos = listOf(photo()), dirty = false)
        assertFalse(entry.syncable)
        val deleted = reviewCandidate(entry, false)
        assertTrue(deleted.deleted)
        assertTrue(deleted.candidate)
        assertFalse(deleted.syncable)
        assertFalse(deleted.dirty)
        assertEquals(entry.photos, deleted.photos)
    }
    @Test fun keepingOnlyChangesReviewStateAndPreservesContent() {
        val entry = Entry(candidate = true, scanDay = "2024-01-01", photos = listOf(photo()), body = "记录")
        val kept = reviewCandidate(entry, true)
        assertFalse(kept.candidate); assertTrue(kept.dirty); assertTrue(kept.syncable)
        assertEquals(entry.photos, kept.photos); assertEquals(entry.body, kept.body)
        assertNull(kept.scanDay); assertEquals(entry.createdAt, kept.createdAt)
    }
    @Test fun unlimitedPhotosAreRetainedAndForeignPhotosAreRejected() {
        val entry = Entry(candidate = true, scanDay = "2024-01-01", photos = List(25) { photo() })
        assertEquals(25, reviewCandidate(entry, true).photos.size)
        assertEquals(entry.photos, Entry.parse(reviewCandidate(entry, true).json()).photos)
        assertThrows(IllegalArgumentException::class.java) { reviewCandidate(entry, true, listOf(photo())) }
        assertThrows(IllegalArgumentException::class.java) { reviewCandidate(entry, true, emptyList()) }
    }
    @Test fun unknownDatesMustBeConfirmedBeforeKeeping() {
        val entry = Entry(candidate = true, scanDay = "unknown", photos = listOf(photo()))
        assertThrows(IllegalArgumentException::class.java) { reviewCandidate(entry, true) }
        assertTrue(reviewCandidate(entry, false).deleted)
    }
    @Test fun candidateMetadataIsLocalWhilePhotoCountsAreUnlimited() {
        val entry = Entry(candidate = true, scanDay = "2024-01-01", photos = List(25) { photo() })
        assertFalse(entry.json().contains("candidate")); assertFalse(entry.json().contains("scanDay"))
        assertEquals(entry.photos, Entry.parse(entry.json(), candidate = true, scanDay = entry.scanDay).photos)
        assertEquals(entry.photos, Entry.parse(entry.json()).photos)
    }
    @Test fun formalRecordsCannotBeProcessedByCandidateGestures() {
        assertThrows(IllegalArgumentException::class.java) { reviewCandidate(Entry(photos = listOf(photo())), false) }
    }
    @Test fun changingCandidateDateMovesItOutOfTheOldScanDay() {
        val time = Instant.parse("2024-02-02T12:00:00Z").toEpochMilli()
        val moved = Entry(candidate = true, scanDay = "2020-01-01").withJournalTime(time)
        assertEquals(time, moved.createdAt)
        assertNotEquals("2020-01-01", moved.scanDay)
        assertNull(Entry().withJournalTime(time).scanDay)
    }

}
