package com.firzen.dailyone

import android.database.sqlite.SQLiteDatabase
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
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CloudSyncTest {
    private lateinit var store: JournalStore
    private val name = "sync-${UUID.randomUUID()}"
    private val dav = CloudConfig("https://dav.example.com", "fixture", "fixture")
    private val s3 = CloudConfig(provider = CloudProvider.S3, s3 = S3Config("https://s3.example.com", "auto", "journals", "fixture", "fixture"))
    @Before fun setUp() = runBlocking {
        store = JournalStore(RuntimeEnvironment.getApplication(), "$name.db", name); store.load()
    }
    @After fun tearDown() {
        store.close(); RuntimeEnvironment.getApplication().deleteDatabase("$name.db"); store.mediaDir.deleteRecursively()
    }
    private class MemoryStorage : CloudStorage {
        val entries = linkedMapOf<String, Entry>()
        val books = linkedMapOf<String, Notebook>()
        val photos = linkedMapOf<String, ByteArray>()
        val hashes = mutableSetOf<String>()
        val batches = linkedMapOf<String, String>()
        val batchPutIds = mutableListOf<String>()
        var batchPuts = 0
        var failBatchAt = Int.MAX_VALUE
        var failReadbackOnce = false
        override fun listScanHashes() = hashes - batches.flatMap { parseScanBatch(it.key, it.value).hashes }.toSet()
        override fun listScanBatches() = batches.keys.toSet()
        override fun getScanBatch(id: String) = batches.getValue(id)
        override fun putScanBatch(id: String, document: String) {
            batchPuts++; batchPutIds += id
            if (failUpload || batchPuts == failBatchAt) throw IOException("interrupted batch")
            val batch = parseScanBatch(id, document)
            batches[id] = document; hashes.addAll(batch.hashes)
            if (failReadbackOnce) { failReadbackOnce = false; throw IOException("uncertain write") }
        }
        override fun getScanHash(hash: String) { check(hash in hashes) }
        override fun putScanHash(hash: String) { if (failUpload) throw IOException("interrupted"); hashes.add(hash) }
        var version = 0
        var failUpload = false
        override fun ensureCollections() {}
        override fun listEntries() = entries.mapValues { requireNotNull(it.value.etag) }
        override fun listNotebooks() = books.mapValues { requireNotNull(it.value.etag) }
        override fun listPhotos() = photos.keys
        override fun getEntry(id: String) = entries.getValue(id)
        override fun getNotebook(id: String) = books.getValue(id)
        override fun putEntry(entry: Entry): String {
            if (failUpload) throw IOException("interrupted")
            check(entry.etag == entries[entry.id]?.etag) { "Wrong target ETag" }
            val tag = "\"entry-${++version}\""; entries[entry.id] = entry.copy(dirty = false, etag = tag); return tag
        }
        override fun putNotebook(book: Notebook): String {
            check(book.etag == books[book.id]?.etag)
            val tag = "\"book-${++version}\""; books[book.id] = book.copy(dirty = false, etag = tag); return tag
        }
        override fun putPhoto(name: String, file: File) { photos.putIfAbsent(name, file.readBytes()) }
        override fun downloadPhoto(name: String, destination: File) { destination.writeBytes(photos.getValue(name)) }
        override fun cancelRequests() {}
    }
    private fun seedHistory(count: Int) {
        store.writableDatabase.apply {
            beginTransaction()
            try {
                repeat(count) { execSQL("INSERT OR IGNORE INTO scan_history(sha256,dirty) VALUES(?,1)", arrayOf("%064x".format(it + 1))) }
                setTransactionSuccessful()
            } finally { endTransaction() }
        }
    }
    @Test fun batchesBoundRequestsAndResumeOnlyUnacknowledgedWorkAfterReopening() = runBlocking {
        seedHistory(1025)
        val remote = MemoryStorage().apply { failBatchAt = 2 }
        try { SyncEngine(store) { remote }.sync(s3); fail("Expected interruption") } catch (_: IOException) {}
        val completedId = remote.batches.keys.single()
        assertEquals(512, remote.hashes.size)
        assertEquals(513, store.scanHistoryHashes(pendingOnly = true).size)
        val reserved = store.pendingScanBatches().map { it.id }.toSet()
        store.close(); store = JournalStore(RuntimeEnvironment.getApplication(), "$name.db", name); store.load()
        assertEquals(reserved, store.pendingScanBatches().map { it.id }.toSet())
        remote.failBatchAt = Int.MAX_VALUE
        val stages = mutableListOf<String>()
        SyncEngine(store) { remote }.sync(s3) { stages += it.stage }
        assertEquals(1025, remote.hashes.size); assertEquals(3, remote.batches.size)
        assertEquals(1, remote.batchPutIds.count { it == completedId })
        assertEquals(4, remote.batchPuts) // three batches plus one failed attempt, instead of 1025 objects.
        assertTrue(stages.any { it.contains("已完成 512/1025") })
        assertFalse(store.scanHistoryPending.value)
        val puts = remote.batchPuts
        SyncEngine(store) { remote }.sync(s3)
        assertEquals(puts, remote.batchPuts)
    }
    @Test fun uncertainUploadIsReconciledFromRemoteBatchWithoutCreatingOrSendingAnotherBatch() = runBlocking {
        seedHistory(40)
        val remote = MemoryStorage().apply { failReadbackOnce = true }
        try { SyncEngine(store) { remote }.sync(s3); fail("Expected uncertain write") } catch (_: IOException) {}
        assertTrue(store.scanHistoryPending.value)
        val id = remote.batches.keys.single()
        store.close(); store = JournalStore(RuntimeEnvironment.getApplication(), "$name.db", name); store.load()
        SyncEngine(store) { remote }.sync(s3)
        assertEquals(setOf(id), store.knownScanBatches()); assertEquals(1, remote.batchPuts)
        assertEquals(40, store.scanHistoryHashes().size); assertFalse(store.scanHistoryPending.value)
    }
    @Test fun pendingBatchPayloadSurvivesNewScansAndInvalidRemoteBatchCannotClearHistory() = runBlocking {
        seedHistory(20)
        val first = store.pendingScanBatches().single()
        seedHistory(21)
        val next = store.pendingScanBatches()
        assertTrue(next.contains(first)); assertEquals(2, next.size)
        assertEquals(21, next.flatMap { it.hashes }.distinct().size)
        try { store.acceptScanBatch(first.id, first.document.replace("version", "damaged")); fail("Expected invalid batch") }
        catch (_: IllegalArgumentException) {}
        assertEquals(21, store.scanHistoryHashes(pendingOnly = true).size)
        assertTrue(store.scanHistoryPending.value)
    }
    @Test fun failedBatchAcknowledgementRollsBackEveryHashAndReceipt() = runBlocking {
        seedHistory(30)
        val batch = store.pendingScanBatches().single()
        store.writableDatabase.execSQL("CREATE TRIGGER fail_batch BEFORE INSERT ON scan_batches BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        try { store.acceptScanBatch(batch.id, batch.document); fail("Expected acknowledgement failure") }
        catch (_: android.database.sqlite.SQLiteException) {}
        assertEquals(30, store.scanHistoryHashes(pendingOnly = true).size)
        assertTrue(store.knownScanBatches().isEmpty())
        store.writableDatabase.execSQL("DROP TRIGGER fail_batch")
        store.acceptScanBatch(batch.id, batch.document)
        assertEquals(setOf(batch.id), store.knownScanBatches()); assertFalse(store.scanHistoryPending.value)
    }
    @Test fun migrationFromSixKeepsHistoryCheckpointsMediaAndDiaries() = runBlocking {
        val source = "${UUID.randomUUID()}.png"
        File(store.mediaDir, source).writeText("original")
        val entry = Entry(photos = listOf(source), body = "已有日记")
        store.save(entry); store.recordScan("old-source", null, null) { null }
        seedHistory(3)
        store.writableDatabase.apply { execSQL("DROP TABLE scan_batches"); version = 6 }
        store.close(); store = JournalStore(RuntimeEnvironment.getApplication(), "$name.db", name); store.load()
        assertEquals(entry, store.get(entry.id)); assertTrue(store.scanned("old-source"))
        assertEquals("original", File(store.mediaDir, source).readText())
        assertEquals(3, store.scanHistoryHashes(pendingOnly = true).size)
        assertEquals(3, store.pendingScanBatches().single().hashes.size)
    }
    @Test fun discardedCandidateAndNegativeHistorySyncWithoutUploadingRejectedMedia() = runBlocking {
        val remote = MemoryStorage()
        val photo = "${UUID.randomUUID()}.png"
        File(store.mediaDir, photo).writeText("rejected original bytes")
        store.recordScan("old-rejected", 1704103200000, photo) { null }
        store.review(store.entries.value.single().id, false)
        val negative = "b".repeat(64)
        store.recordScanContent("negative", negative, requireNotNull(store.photoScanIndex()), complete = true) { null }
        SyncEngine(store) { remote }.sync(s3)
        val expected = setOf(fileSha256(File(store.mediaDir, photo)), negative)
        assertEquals(expected, remote.hashes)
        assertTrue(remote.entries.isEmpty()); assertTrue(remote.photos.isEmpty())
        assertFalse(store.scanHistoryPending.value)
        val secondName = "$name-restored"
        val restored = JournalStore(RuntimeEnvironment.getApplication(), "$secondName.db", secondName)
        try {
            restored.load(); SyncEngine(restored) { remote }.sync(s3)
            assertEquals(expected, restored.scanHistoryHashes()); assertTrue(restored.entries.value.isEmpty())
            val index = requireNotNull(restored.photoScanIndex())
            for (hash in expected) {
                assertEquals(ScanContentAction.SKIPPED,
                    restored.recordScanContent(hash, hash, index, complete = true) { null }.action)
            }
            assertTrue(restored.entries.value.isEmpty())
        } finally {
            restored.close(); RuntimeEnvironment.getApplication().deleteDatabase("$secondName.db"); restored.mediaDir.deleteRecursively()
        }
    }
    @Test fun historyUploadFailureStaysPendingAndTargetSwitchMergesHistory() = runBlocking {
        val first = MemoryStorage(); val second = MemoryStorage()
        val hash = "c".repeat(64); val remoteOnly = "d".repeat(64)
        store.recordScanContent("negative", hash, requireNotNull(store.photoScanIndex()), complete = true) { null }
        first.failUpload = true
        try { SyncEngine(store) { first }.sync(dav); fail("Expected history upload failure") } catch (_: IOException) {}
        assertTrue(store.scanHistoryPending.value); assertEquals(setOf(hash), store.scanHistoryHashes(pendingOnly = true))
        first.failUpload = false; SyncEngine(store) { first }.sync(dav)
        assertFalse(store.scanHistoryPending.value); assertEquals(setOf(hash), first.hashes)
        second.hashes.add(remoteOnly); SyncEngine(store) { second }.sync(s3)
        assertEquals(setOf(hash, remoteOnly), second.hashes)
        assertEquals(setOf(hash, remoteOnly), store.scanHistoryHashes())
        SyncEngine(store) { first }.sync(dav)
        assertEquals(setOf(hash, remoteOnly), first.hashes)
    }
    @Test fun switchesBothWaysWithoutReusingEtagsDeletingRecordsOrDuplicatingEqualContent() = runBlocking {
        val first = MemoryStorage(); val second = MemoryStorage()
        val photo = "${UUID.randomUUID()}.jpg"
        File(store.mediaDir, photo).writeText("original bytes")
        val common = Entry(body = "两端相同", photos = listOf(photo))
        store.save(common)
        val engine = SyncEngine(store) { if (it.provider == CloudProvider.WEBDAV) first else second }
        assertEquals(0, engine.sync(dav))
        val oldSnapshot = first.entries.toMap()
        second.entries[common.id] = common.copy(updatedAt = 1, dirty = false, etag = "\"s3-common\"")
        second.photos[photo] = "original bytes".toByteArray()
        val cloudOnly = Entry(body = "仅云端", dirty = false, etag = "\"s3-only\"")
        second.entries[cloudOnly.id] = cloudOnly
        val localOnly = Entry(body = "仅本机")
        store.save(localOnly)
        assertEquals(0, engine.sync(s3))
        assertEquals(oldSnapshot, first.entries)
        assertEquals(setOf(common.id, cloudOnly.id, localOnly.id), store.entries.value.map { it.id }.toSet())
        assertEquals(3, second.entries.size)
        assertEquals(0, engine.sync(s3))
        assertEquals(3, store.entries.value.size)
        assertEquals(0, engine.sync(dav))
        assertEquals(3, first.entries.size)
        assertFalse(store.entries.value.any { it.deleted || it.dirty })
        assertEquals("original bytes", File(store.mediaDir, photo).readText())
    }
    @Test fun differentContentPreservesConflictCopyAndDownloadsMedia() = runBlocking {
        val remote = MemoryStorage()
        val local = Entry(body = "本机修改")
        store.save(local)
        val name = "${UUID.randomUUID()}.mp4"
        remote.photos[name] = "video bytes".toByteArray()
        remote.entries[local.id] = local.copy(body = "云端修改", photos = emptyList(), videos = listOf(name), dirty = false, etag = "\"remote\"")
        assertEquals(1, SyncEngine(store) { remote }.sync(s3))
        assertEquals(setOf("本机修改", "云端修改"), store.entries.value.map { it.body }.toSet())
        assertEquals("video bytes", File(store.mediaDir, name).readText())
        assertEquals(2, remote.entries.size)
    }
    @Test fun interruptedSyncRetainsPendingChangesAndCandidateRecordsStayLocal() = runBlocking {
        val remote = MemoryStorage(); val entry = Entry(body = "待同步")
        store.save(entry)
        val candidate = Entry(body = "候选", candidate = true, dirty = false)
        store.save(candidate)
        remote.failUpload = true
        try { SyncEngine(store) { remote }.sync(s3); fail("Expected interruption") } catch (_: IOException) {}
        assertTrue(requireNotNull(store.get(entry.id)).dirty)
        remote.failUpload = false
        SyncEngine(store) { remote }.sync(s3)
        assertFalse(requireNotNull(store.get(entry.id)).dirty)
        assertFalse(remote.entries.containsKey(candidate.id))
        assertTrue(requireNotNull(store.get(candidate.id)).candidate)
    }
    @Test fun migrationAndTargetBindingSurviveReopeningWithoutRemovingContent() = runBlocking {
        val original = Entry(body = "旧版数据", etag = "\"old\"", dirty = false)
        store.save(original); store.close()
        val context = RuntimeEnvironment.getApplication()
        SQLiteDatabase.openDatabase(context.getDatabasePath("$name.db").path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP TABLE sync_state"); it.version = 4
        }
        store = JournalStore(context, "$name.db", name); store.load()
        assertEquals(original, store.get(original.id))
        store.bindSyncTarget(s3.targetId())
        assertNull(store.get(original.id)?.etag); assertTrue(requireNotNull(store.get(original.id)).dirty)
        store.write(requireNotNull(store.get(original.id)).copy(etag = "\"s3\"", dirty = false))
        store.close(); store = JournalStore(context, "$name.db", name); store.load()
        store.bindSyncTarget(s3.targetId())
        assertEquals("\"s3\"", store.get(original.id)?.etag)
        assertFalse(requireNotNull(store.get(original.id)).dirty)
        assertEquals(original.body, store.get(original.id)?.body)
    }
}
