package com.firzen.dailyone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Uses only uniquely named databases and fixture photos. Never scans or edits the user's gallery. */
@RunWith(AndroidJUnit4::class)
class ScanIntegrationTest {
    private lateinit var context: Context
    private lateinit var store: JournalStore
    private lateinit var dbName: String
    private lateinit var mediaName: String
    private val fixtureFiles = mutableListOf<File>()
    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        dbName = "scan-test-$id.db"; mediaName = "scan-test-$id"
        store = JournalStore(context, dbName, mediaName)
    }
    @After fun tearDown() {
        store.close(); context.deleteDatabase(dbName); store.mediaDir.deleteRecursively()
        fixtureFiles.forEach { it.delete() }
    }
    private fun photo() = "${UUID.randomUUID()}.jpg"
    @Test fun migrationPreservesOldJournalsAndSyncMetadata() = runBlocking {
        store.close()
        val original = Entry(title = "旧日记", body = "必须保留", etag = "\"v1\"", dirty = false)
        context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE entries (id TEXT PRIMARY KEY, document TEXT NOT NULL, dirty INTEGER NOT NULL, etag TEXT)")
            db.execSQL("INSERT INTO entries VALUES(?,?,0,?)", arrayOf(original.id, original.json(), original.etag))
            db.version = 1
        }
        store = JournalStore(context, dbName, mediaName)
        store.load()
        assertEquals(original, store.entries.value.single())
        assertFalse(store.scanned("new-source"))
    }
    @Test fun checkpointAndDailyCandidateCommitTogetherAndSurviveReopening() = runBlocking {
        store.load()
        val time = 1704103200000L
        val first = photo()
        store.recordScan("one", time, first) { null }
        store.recordScan("one", time, photo()) { null }
        store.recordScan("two", time + 1000, photo()) { null }
        assertEquals(2, store.entries.value.single().photos.size)
        assertTrue(store.entries.value.single().candidate)
        assertFalse(store.entries.value.single().syncable)
        store.close(); store = JournalStore(context, dbName, mediaName); store.load()
        assertTrue(store.scanned("one")); assertTrue(store.scanned("two"))
        assertEquals(2, store.entries.value.single().photos.size)
    }
    @Test fun keepingDeletingAndEditingDoNotReceiveFurtherScannerAppends() = runBlocking {
        store.load(); val time = 1704103200000L
        store.recordScan("one", time, photo()) { null }
        val first = store.entries.value.single()
        store.review(first.id, true)
        store.recordScan("two", time + 1000, photo()) { null }
        assertEquals(1, store.entries.value.first { it.id == first.id }.photos.size)
        val second = store.entries.value.first { it.candidate }
        store.recordScan("three", time + 2000, photo()) { second.id }
        assertEquals(2, store.entries.value.count { it.candidate })
        store.review(second.id, false)
        assertTrue(store.scanned("two"))
        assertFalse(store.entries.value.first { it.id == second.id }.syncable)
    }
    @Test fun reviewUndoRefusesToOverwriteLaterEdits() = runBlocking {
        store.load(); store.recordScan("one", 1704103200000L, photo()) { null }
        val action = requireNotNull(store.review(store.entries.value.single().id, true))
        assertTrue(store.restoreIfUnchanged(action.first, action.second))
        val next = requireNotNull(store.review(action.first.id, true))
        store.save(next.second.copy(body = "后来写的文字"))
        assertFalse(store.restoreIfUnchanged(next.first, next.second))
        assertEquals("后来写的文字", store.entries.value.single().body)
    }
    @Test fun stoppingRetainsPartialCandidatesAndResumeDoesNotRepeatProcessedPhotos() = runBlocking {
        store.load()
        val inputs = (1..3).map { index ->
            val file = File(context.cacheDir, "scan-fixture-${UUID.randomUUID()}.jpg"); fixtureFiles += file
            val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }; bitmap.recycle()
            ScanPhoto(Uri.fromFile(file), "fixture-$index", 1704103200000L + index * 1000)
        }
        val factory = { object : PersonClassifier {
            override fun containsPerson(bitmap: Bitmap) = true
            override fun close() = Unit
        } }
        val scan = PhotoScanner(context, store, { inputs }, factory)
        val result = scan.run({ null }) { if (it.processed == 1) scan.stop.set(true) }
        assertEquals(1, result.processed); assertEquals(1, store.entries.value.single().photos.size)
        val resumed = PhotoScanner(context, store, { inputs }, factory).run({ null }) {}
        assertEquals(1, resumed.skipped); assertEquals(3, store.entries.value.single().photos.size)
    }
    @Test fun bundledDetectorRecognizesPersonAndRejectsBlankImage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val person = instrumentation.context.assets.open("person.jpg").use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
        } ?: error("Fixture missing")
        val blank = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        try { PersonDetector(context).use { detector ->
            assertTrue("Bundled model must recognize the official person fixture", detector.containsPerson(person))
            assertFalse(detector.containsPerson(blank))
        } } finally { person.recycle(); blank.recycle() }
    }
}
