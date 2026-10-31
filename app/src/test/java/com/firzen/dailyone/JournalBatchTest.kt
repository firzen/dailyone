package com.firzen.dailyone

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class JournalBatchTest {
    private fun photo() = "${UUID.randomUUID()}.jpg"
    @Test fun mergesChronologicallyAndPreservesAllTextPhotosAndDate() {
        val first = Entry(createdAt = 100, title = "最早标题", body = "正文一", photos = listOf(photo()), etag = "v1")
        val second = Entry(createdAt = 200, title = "第二标题", body = "正文二", photos = List(30) { photo() })
        val plan = journalBatch(listOf(second, first), true, 300)
        val result = plan.first().second
        assertEquals(first.id, result.id); assertEquals(100L, result.createdAt)
        assertEquals("最早标题", result.title); assertEquals("正文一\n\n第二标题\n正文二", result.body)
        assertEquals(first.photos + second.photos, result.photos); assertEquals("v1", result.etag)
        assertTrue(result.dirty); assertTrue(plan[1].second.deleted)
        assertTrue(plan[1].second.dirty)
    }
    @Test fun candidateOnlyMergeStaysLocalAndDeduplicatesSharedReferences() {
        val name = photo()
        val a = Entry(createdAt = 100, candidate = true, scanDay = "2024-01-01", photos = listOf(name))
        val b = Entry(createdAt = 200, candidate = true, scanDay = "2024-01-02", photos = listOf(name, photo()))
        val plan = journalBatch(listOf(a, b), true)
        assertTrue(plan.first().second.candidate); assertFalse(plan.first().second.dirty)
        assertFalse(plan.first().second.syncable); assertEquals(2, plan.first().second.photos.size)
        assertFalse(plan[1].second.syncable)
    }
    @Test fun mixedMergeProducesFormalResultButCandidateTombstonesStayLocal() {
        val a = Entry(createdAt = 100, candidate = true, scanDay = "2024-01-01", photos = listOf(photo()))
        val b = Entry(createdAt = 200, body = "已有正式内容")
        val plan = journalBatch(listOf(a, b), true)
        assertFalse(plan.first().second.candidate); assertTrue(plan.first().second.dirty)
        assertTrue(plan[1].second.deleted); assertTrue(plan[1].second.syncable)
        val reverse = journalBatch(listOf(b.copy(createdAt = 50), a), true)
        assertTrue(reverse[1].second.candidate); assertFalse(reverse[1].second.dirty)
    }
    @Test fun batchDeleteDistinguishesFormalAndCandidateSyncStatus() {
        val a = Entry(candidate = false, etag = "v1")
        val b = Entry(candidate = true, scanDay = "unknown")
        val plan = journalBatch(listOf(a,b), false, 900)
        assertTrue(plan.all { it.second.deleted })
        assertEquals("v1", plan[0].second.etag); assertTrue(plan[0].second.dirty)
        assertFalse(plan[1].second.dirty)
        assertEquals(a.photos, plan[0].second.photos)
    }
    @Test fun knownDateWinsOverUnknownAndInvalidSelectionsAreRejected() {
        val unknown = Entry(createdAt = 0, candidate = true, scanDay = "unknown")
        val dated = Entry(createdAt = 100, candidate = true, scanDay = "2024-01-01")
        assertEquals(dated.id, journalBatch(listOf(unknown, dated), true).first().second.id)
        assertThrows(IllegalArgumentException::class.java) { journalBatch(listOf(dated), true) }
        assertThrows(IllegalArgumentException::class.java) { journalBatch(listOf(dated, dated), true) }
        assertThrows(IllegalArgumentException::class.java) { journalBatch(listOf(dated.copy(deleted = true)), false) }
    }
}
