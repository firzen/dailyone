package com.firzen.dailyone

import org.junit.Assert.*
import org.junit.Test

class EntryTest {
    @Test fun roundTripPreservesContentAndDeletion() {
        val entry = Entry(title = "今天\n\"日记\"", body = "中文 🌿", photos = listOf("12345678-1234-1234-1234-123456789abc.jpg"), deleted = true)
        val restored = Entry.parse(entry.json(), "\"version-2\"")
        assertEquals(entry.copy(dirty = false, etag = "\"version-2\""), restored)
    }
    @Test fun unchangedRemoteKeepsUnsentLocalEdits() {
        assertEquals(MergeAction.KEEP_LOCAL, mergeAction(Entry(dirty = true, etag = "a"), "a"))
    }
    @Test fun concurrentEditRequiresConflictCopyEvenWhenRemoteClockIsOlder() {
        assertEquals(MergeAction.CONFLICT, mergeAction(Entry(updatedAt = 999, dirty = true, etag = "a"), "b"))
    }
    @Test fun newDeviceTakesRemote() {
        assertEquals(MergeAction.TAKE_REMOTE, mergeAction(null, "a"))
    }
    @Test fun cleanLocalTakesRemoteIncludingTombstones() {
        assertEquals(MergeAction.TAKE_REMOTE, mergeAction(Entry(dirty = false, etag = "a"), "b"))
    }
    @Test fun concurrentRemoteDeletePreservesLocalEdits() {
        assertEquals(MergeAction.CONFLICT, mergeAction(Entry(body = "不能丢", dirty = true, etag = "a"), "deleted-version"))
    }
    @Test fun unsafePhotoPathsAreRejected() {
        val json = Entry().json().replace("\"photos\":[]", "\"photos\":[\"../../secret\"]")
        assertThrows(IllegalArgumentException::class.java) { Entry.parse(json) }
        assertFalse(validPhoto("../../secret.jpg"))
    }
    @Test fun futureFormatsAreRejected() {
        val json = org.json.JSONObject(Entry().json()).put("version", 99).toString()
        assertThrows(IllegalArgumentException::class.java) { Entry.parse(json) }
    }
}
