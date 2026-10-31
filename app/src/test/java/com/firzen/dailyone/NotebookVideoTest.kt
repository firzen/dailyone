package com.firzen.dailyone

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NotebookVideoTest {
    private val photo = "12345678-1234-1234-1234-123456789abc.jpg"
    private val video = "12345678-1234-1234-1234-123456789abc.mov"
    @Test fun legacyEntryLoadsIntoDefaultNotebook() {
        val old = JSONObject(Entry().json()).put("version", 1).apply { remove("notebookId") }
        val restored = Entry.parse(old.toString())
        assertEquals(DEFAULT_NOTEBOOK, restored.notebookId); assertTrue(restored.videos.isEmpty())
    }
    @Test fun videoOrderAndNotebookSurviveRoundTrip() {
        val book = Notebook(name = "宝宝")
        val entry = Entry(notebookId = book.id, photos = listOf(photo), videos = listOf(video), mediaOrder = listOf(video, photo))
        assertEquals(entry.copy(dirty = false), Entry.parse(entry.json()))
        assertEquals(book.copy(dirty = false), Notebook.parse(book.json()))
    }
    @Test fun crossNotebookMergeRequiresTargetAndPreservesVideoOrder() {
        val a = Entry(photos = listOf(photo), createdAt = 10)
        val b = Entry(notebookId = Notebook(name = "其他").id, videos = listOf(video), createdAt = 20)
        assertThrows(IllegalArgumentException::class.java) { journalBatch(listOf(a, b), true) }
        val merged = journalBatch(listOf(a, b), true, targetNotebook = b.notebookId).first().second
        assertEquals(b.notebookId, merged.notebookId); assertEquals(listOf(photo, video), merged.attachments)
    }
    @Test fun rejectsVideoTraversalAndNonVideoFile() {
        assertFalse(validVideo("../x.mp4")); assertFalse(validVideo(photo))
        val raw = JSONObject(Entry().json()).put("videos", org.json.JSONArray().put("../x.mp4"))
        assertThrows(IllegalArgumentException::class.java) { Entry.parse(raw.toString()) }
    }
}
