package com.firzen.dailyone

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OriginalPhotoTest {
    @Test fun originalsAndLegacyRestorationPreserveContentAndConcurrentEdits() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = UUID.randomUUID().toString()
        val store = JournalStore(context, "$key.db", key)
        val source = File(context.cacheDir, "$key.jpg")
        val png = File(context.cacheDir, "$key.png")
        try {
            store.load()
            val bitmap = Bitmap.createBitmap(3200, 1700, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.MAGENTA)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 96, it) }
            png.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            ExifInterface(source).apply { setLatLong(31.2304, 121.4737); saveAttributes() }
            val imported = store.importPhoto(Uri.fromFile(source))
            assertEquals(fileSha256(source), fileSha256(File(store.mediaDir, imported)))
            assertEquals(31.2304, requireNotNull(store.knownPhotoLocation(imported)).latitude, 0.00001)
            val alpha = store.importPhoto(Uri.fromFile(png))
            assertTrue(alpha.endsWith(".png"))
            assertEquals(fileSha256(png), fileSha256(File(store.mediaDir, alpha)))
            val old = "${UUID.randomUUID()}.jpg"
            File(store.mediaDir, old).writeBytes(store.encodePhoto(Uri.fromFile(source)))
            assertNotEquals(fileSha256(source), fileSha256(File(store.mediaDir, old)))
            val entry = Entry(title = "标题", body = "文字", createdAt = 100, photos = listOf(alpha, old), dirty = false)
            val candidate = Entry(photos = listOf(old), candidate = true, dirty = false)
            store.save(entry); store.save(candidate)
            val result = GeoBackfill(context, store) { listOf(Uri.fromFile(source), Uri.fromFile(png)) }.run {}
            assertEquals(1, result.replaced); assertEquals(0, result.remaining)
            val restored = requireNotNull(store.get(entry.id))
            assertEquals(100L, restored.createdAt); assertEquals("文字", restored.body)
            assertEquals(alpha, restored.photos.first()); assertTrue(restored.dirty)
            val replacement = restored.photos.last()
            assertNotEquals(old, replacement)
            assertEquals(fileSha256(source), fileSha256(File(store.mediaDir, replacement)))
            assertEquals(31.2304, requireNotNull(restored.location).latitude, 0.00001)
            assertTrue(requireNotNull(store.get(candidate.id)).candidate)
            assertFalse(requireNotNull(store.get(candidate.id)).dirty)
            // A stale editor must preserve its new text without putting the compressed image back.
            store.save(entry.copy(body = "恢复时新写的内容"))
            assertEquals(restored.photos, store.get(entry.id)?.photos)
            assertEquals("恢复时新写的内容", store.get(entry.id)?.body)
            assertTrue(File(store.mediaDir, old).exists())
        } finally {
            store.close(); context.deleteDatabase("$key.db"); store.mediaDir.deleteRecursively()
            source.delete(); png.delete()
        }
    }
    @Test fun unrelatedPhotosAndStoppingNeverChangeUnmatchedEntries() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = UUID.randomUUID().toString(); val store = JournalStore(context, "$key.db", key)
        try {
            store.load()
            val name = "${UUID.randomUUID()}.jpg"; File(store.mediaDir, name).writeText("unmatched")
            val entry = Entry(photos = listOf(name), title = "保留")
            store.save(entry)
            val worker = GeoBackfill(context, store) { emptyList() }
            worker.stop.set(true)
            assertEquals(1, worker.run {}.remaining)
            assertEquals(entry, store.get(entry.id))
        } finally { store.close(); context.deleteDatabase("$key.db"); store.mediaDir.deleteRecursively() }
    }
}
