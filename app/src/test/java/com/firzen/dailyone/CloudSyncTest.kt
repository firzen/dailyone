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
