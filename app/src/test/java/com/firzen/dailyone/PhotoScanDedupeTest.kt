package com.firzen.dailyone

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PhotoScanDedupeTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val name = "scan-content-${UUID.randomUUID()}"
    private lateinit var store: JournalStore
    private val files = mutableListOf<File>()
    @Before fun setUp() = runBlocking { store = JournalStore(context, "$name.db", name); store.load() }
    @After fun tearDown() {
        store.close(); context.deleteDatabase("$name.db"); store.mediaDir.deleteRecursively(); files.forEach { it.delete() }
    }
    private fun photo(color: Int = Color.RED): File = File.createTempFile("scan-fixture", ".png", context.cacheDir).also { file ->
        files += file
        val bitmap = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    private fun input(file: File, key: String = UUID.randomUUID().toString()) = ScanPhoto(Uri.fromFile(file), key, 1704103200000)
    private suspend fun scan(inputs: List<ScanPhoto>, person: Boolean = true, detected: () -> Unit = {}): ScanProgress =
        PhotoScanner(context, store, { inputs }) {
            object : PersonClassifier {
                override fun containsPerson(bitmap: Bitmap): Boolean { detected(); return person }
                override fun close() {}
            }
        }.run({ null }) {}

    @Test fun identicalBytesWithDifferentSourcesCreateOnlyOneCandidateAndSurviveReopenAndRejection() = runBlocking {
        val source = photo(); val copy = photo().also { source.copyTo(it, overwrite = true) }
        var detected = 0
        val result = scan(listOf(input(source), input(copy))) { detected++ }
        assertEquals(1, detected); assertEquals(1, result.matched); assertEquals(1, result.skipped)
        val entry = store.entries.value.single(); assertEquals(1, entry.photos.size)
        store.review(entry.id, false)
        store.close(); store = JournalStore(context, "$name.db", name); store.load()
        val rejected = requireNotNull(store.get(entry.id)); assertTrue(rejected.deleted)
        val repeated = scan(listOf(input(source))) { error("Rejected content must not be classified") }
        assertEquals(1, repeated.skipped); assertEquals(rejected, store.get(entry.id))
        assertArrayEquals(source.readBytes(), File(store.mediaDir, entry.photos.single()).readBytes())
    }
    @Test fun negativeDecisionsSurviveRemoteHistoryRestoreWithoutAnyRetainedImage() = runBlocking {
        val source = photo()
        assertEquals(0, scan(listOf(input(source)), person = false).matched)
        val hashes = store.scanHistoryHashes(); assertEquals(1, hashes.size)
        assertTrue(store.entries.value.isEmpty()); assertTrue(store.mediaDir.listFiles().orEmpty().isEmpty())
        // Simulate a new installation with only the synced history, no checkpoints or photos.
        store.close(); context.deleteDatabase("$name.db")
        store = JournalStore(context, "$name.db", name); store.load()
        hashes.forEach { store.acceptScanHash(it) }
        val restored = scan(listOf(input(source))) { error("Remote negative decision must skip detection") }
        assertEquals(1, restored.skipped); assertTrue(store.entries.value.isEmpty())
        assertFalse(store.scanHistoryPending.value)
    }
    @Test fun restoredDiaryMediaDeduplicatesWithoutLocalCheckpointsAndPreservesDiary() = runBlocking {
        val source = photo(); val name = "${UUID.randomUUID()}.png"
        source.copyTo(File(store.mediaDir, name))
        val original = Entry(body = "已恢复的日记", photos = listOf(name), dirty = false, etag = "restored")
        store.save(original)
        val result = scan(listOf(input(source))) { error("Downloaded original must skip detection") }
        assertEquals(1, result.skipped); assertEquals(original, store.get(original.id))
        assertEquals(1, store.entries.value.size); assertEquals(setOf(fileSha256(source)), store.scanHistoryHashes())
    }
    @Test fun failedAndStoppedClassificationRemainRetryableAndDifferentContentIsAdded() = runBlocking {
        val first = photo(); val second = photo(Color.BLUE)
        val a = input(first); val b = input(second)
        val failed = scan(listOf(a)) { error("classification failed") }
        assertEquals(1, failed.failed); assertFalse(store.scanned(a.key)); assertTrue(store.scanHistoryHashes().isEmpty())
        val stopping = PhotoScanner(context, store, { listOf(a) }) { error("Must not construct detector") }
        stopping.stop.set(true); stopping.run({ null }) {}
        assertFalse(store.scanned(a.key))
        val retry = scan(listOf(a, b)); assertEquals(2, retry.matched)
        assertEquals(2, store.entries.value.single().photos.size); assertEquals(2, store.scanHistoryHashes().size)
    }
    @Test fun legacyDiscardedPhotoAndNegativeCheckpointAreBackfilledWithoutChangingEntries() = runBlocking {
        val source = photo(); val negative = photo(Color.BLUE)
        val name = store.importPhoto(Uri.fromFile(source))
        store.recordScan("old-positive", 1704103200000, name) { null }
        val before = store.entries.value.single()
        store.review(before.id, false)
        val rejected = store.entries.value.single()
        store.recordScan("old-negative", null, null) { null }
        assertTrue(store.scanHistoryHashes().isEmpty())
        store.photoScanIndex()
        assertEquals(setOf(fileSha256(source)), store.scanHistoryHashes())
        val result = scan(listOf(input(negative, "old-negative")), false) { error("Legacy checkpoint must skip detection") }
        assertEquals(1, result.skipped)
        assertEquals(setOf(fileSha256(source), fileSha256(negative)), store.scanHistoryHashes())
        assertEquals(rejected, store.get(before.id))
    }
    @Test fun syncHistoryChangesInvalidateActiveIndexBeforeCandidateCommit() = runBlocking {
        val source = photo(); val hash = fileSha256(source)
        val oldIndex = requireNotNull(store.photoScanIndex())
        store.acceptScanHash(hash)
        val stale = store.recordScanContent("stale", hash, oldIndex, complete = true) { null }
        assertEquals(ScanContentAction.STALE, stale.action); assertFalse(store.scanned("stale"))
        val fresh = requireNotNull(store.photoScanIndex())
        assertEquals(ScanContentAction.SKIPPED, store.recordScanContent("stale", hash, fresh, complete = true) { null }.action)
        assertTrue(store.entries.value.isEmpty())
    }
    @Test fun changingSourceDuringNegativeClassificationDoesNotRememberTheWrongDecision() = runBlocking {
        val source = photo(); val replacement = photo(Color.BLUE); val item = input(source)
        val result = scan(listOf(item), false) { replacement.copyTo(source, overwrite = true) }
        assertEquals(1, result.failed); assertFalse(store.scanned(item.key)); assertTrue(store.scanHistoryHashes().isEmpty())
    }
    @Test fun keepingOnlySomeCandidatePhotosStillRemembersExcludedOnes() = runBlocking {
        val first = photo(); val excluded = photo(Color.BLUE)
        scan(listOf(input(first), input(excluded)))
        val candidate = store.entries.value.single()
        store.review(candidate.id, true, listOf(candidate.photos.first()))
        val kept = store.entries.value.single()
        val result = scan(listOf(input(excluded))) { error("Unselected photo must remain handled") }
        assertEquals(1, result.skipped); assertEquals(kept, store.get(kept.id))
        assertEquals(2, store.scanHistoryHashes().size)
    }
    @Test fun checkpointFailureRollsBackCandidateAndHistoryAndLeavesSourceRetryable() = runBlocking {
        val source = photo(); val item = input(source)
        store.writableDatabase.execSQL("CREATE TRIGGER fail_scan BEFORE INSERT ON scan_source_hashes BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        val failed = scan(listOf(item))
        assertEquals(1, failed.failed); assertFalse(store.scanned(item.key))
        assertTrue(store.entries.value.isEmpty()); assertTrue(store.scanHistoryHashes().isEmpty())
        assertTrue(store.mediaDir.listFiles().orEmpty().isEmpty())
        store.writableDatabase.execSQL("DROP TRIGGER fail_scan")
        assertEquals(1, scan(listOf(item)).matched)
    }
    @Test fun interruptedPreparationPersistsEveryCompletedFingerprintAndReopensWithoutLosingProgress() = runBlocking {
        val sources = listOf(photo(Color.RED), photo(Color.BLUE), photo(Color.GREEN))
        val names = sources.map { source -> "${UUID.randomUUID()}.png".also { source.copyTo(File(store.mediaDir, it)) } }
        val entry = Entry(photos = names, body = "原日记")
        store.save(entry)
        var stopped = false
        assertNull(store.photoScanIndex(stopped = { stopped }) { done, _ -> if (done == 1) stopped = true })
        assertEquals(1, store.scanHistoryHashes().size)
        store.close(); store = JournalStore(context, "$name.db", name); store.load()
        store.readableDatabase.rawQuery("SELECT COUNT(*) FROM photo_fingerprints", null).use { c ->
            c.moveToFirst(); assertEquals(1, c.getInt(0))
        }
        assertEquals(1, store.scanHistoryHashes().size)
        store.photoScanIndex()
        assertEquals(sources.map(::fileSha256).toSet(), store.scanHistoryHashes())
        assertEquals(entry, store.get(entry.id))
        assertEquals(3, store.scanHistoryHashes(pendingOnly = true).size)
    }
    @Test fun cancellingPreparationLeavesCompletedFilesDurable() = runBlocking {
        val sources = listOf(photo(Color.RED), photo(Color.BLUE))
        val names = sources.map { source -> "${UUID.randomUUID()}.png".also { source.copyTo(File(store.mediaDir, it)) } }
        store.save(Entry(photos = names))
        try {
            store.photoScanIndex(progress = { done, _ -> if (done == 1) throw kotlinx.coroutines.CancellationException("fixture") })
            fail("Expected cancellation")
        } catch (_: kotlinx.coroutines.CancellationException) {}
        store.close(); store = JournalStore(context, "$name.db", name); store.load()
        assertEquals(1, store.scanHistoryHashes().size)
        store.photoScanIndex(); assertEquals(2, store.scanHistoryHashes().size)
    }
    @Test fun migrationFromFivePreservesDiariesAndCheckpoints() = runBlocking {
        val entry = Entry(body = "旧资料")
        store.save(entry); store.recordScan("already", null, null) { null }
        store.writableDatabase.apply {
            execSQL("DROP TABLE photo_fingerprints"); execSQL("DROP TABLE scan_history"); execSQL("DROP TABLE scan_source_hashes"); version = 5
        }
        store.close(); store = JournalStore(context, "$name.db", name); store.load()
        assertEquals(entry, store.get(entry.id)); assertTrue(store.scanned("already"))
        val hash = "a".repeat(64); store.acceptScanHash(hash)
        assertEquals(setOf(hash), store.scanHistoryHashes())
    }
}
