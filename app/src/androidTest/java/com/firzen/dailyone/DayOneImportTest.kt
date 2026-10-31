package com.firzen.dailyone

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DayOneImportTest {
    @Test fun importsBooksMediaAndDatesWithoutDuplicatesAndRollsBackBadMedia() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val token = UUID.randomUUID().toString(); val db = "import-test-$token.db"
        val store = JournalStore(context, db, "import-media-$token")
        val root = File(context.cacheDir, token).apply { mkdirs() }
        try {
            store.load()
            val bytes = "fixture original video".toByteArray()
            val md5 = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
            val video = File(root, "videos/$md5.mov").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
            val row = JSONObject().put("uuid", "12345678123412341234123456789ABC").put("creationDate", "2006-05-23T08:03:14Z")
                .put("modifiedDate", "2024-06-08T13:41:15Z").put("text", "# 标题\n\n正文")
                .put("photos", JSONArray().put(JSONObject().put("identifier", "missing")))
                .put("videos", JSONArray().put(JSONObject().put("md5", md5).put("orderInEntry", 0)))
                .put("location", JSONObject().put("latitude", 22.5).put("longitude", 113.9).put("placeName", "原始地点"))
            File(root, "宝宝.json").writeText(JSONObject().put("entries", JSONArray().put(row)).toString())
            val importer = DayOneImporter(store, File(root, "archive"))
            val plan = importer.preview(LocalExportNode(root))
            assertEquals(1, plan.missing); assertEquals(1, plan.media.size)
            assertEquals(0, plan.entries.single().photoLocations.size)
            video.writeText("corrupt")
            try { importer.execute(plan); fail("must reject corrupt media") } catch (_: IllegalArgumentException) { }
            assertTrue(store.entries.value.isEmpty()); assertTrue(store.mediaDir.listFiles().orEmpty().isEmpty())
            video.writeBytes(bytes)
            assertEquals(1, importer.execute(plan))
            val entry = store.entries.value.single()
            assertEquals("标题", entry.title); assertEquals("正文", entry.body)
            assertEquals(java.time.Instant.parse("2006-05-23T08:03:14Z").toEpochMilli(), entry.createdAt)
            assertEquals("原始地点", entry.recordedLocation?.name)
            assertArrayEquals(bytes, File(store.mediaDir, entry.videos.single()).readBytes())
            store.save(entry.copy(body = "正文\n![](dayone-moment://12345678123412341234123456789abc)\n![](dayone-moment:/video/12345678123412341234123456789abc)", dirty = false))
            assertEquals(1, importer.repairImportedText())
            assertEquals(entry, store.get(entry.id))
            assertEquals(0, importer.repairImportedText())
            val book = store.notebooks.value.first { it.id == entry.notebookId }
            store.saveNotebook(book.copy(name = "已改名"))
            val repeat = importer.preview(LocalExportNode(root))
            assertEquals(1, repeat.skipped); assertEquals(0, importer.execute(repeat))
            assertEquals("已改名", store.notebooks.value.first { it.id == book.id }.name)
            store.close()
            JournalStore(context, db, "import-media-$token").use { reopened ->
                reopened.load(); assertEquals(entry, reopened.entries.value.single())
            }
        } finally { store.close(); context.deleteDatabase(db); store.mediaDir.deleteRecursively(); root.deleteRecursively() }
    }
}
