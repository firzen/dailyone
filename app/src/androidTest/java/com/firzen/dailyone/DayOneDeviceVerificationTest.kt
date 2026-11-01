package com.firzen.dailyone

import android.media.MediaPlayer
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Explicit read-only verification of the user's authorized import; not part of normal fixture tests. */
class DayOneDeviceVerificationTest {
    @Test fun importedBooksAndAllFiveVideosAreReadable() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyImported") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        JournalStore(context).use { store ->
            store.load()
            for ((name, count) in listOf("Journal" to 438, "晓宝贝" to 217)) {
                val id = importId("dayone:notebook:$name.json")
                assertEquals(name, store.notebooks.value.first { it.id == id }.name)
                val entries = store.entries.value.filter { it.notebookId == id }
                assertEquals(count, entries.size)
                entries.forEach { entry ->
                    assertFalse(entry.deleted)
                    entry.attachments.forEach { assertTrue(File(store.mediaDir, it).length() > 0) }
                }
            }
            val permission = context.contentResolver.persistedUriPermissions.firstOrNull { it.isReadPermission && it.uri.toString().contains("tree") }
            assertNotNull("Import folder read permission retained", permission)
            val plan = DayOneImporter(store, File(context.filesDir, "imports")).preview(TreeNode.root(context, permission!!.uri))
            assertEquals(655, plan.skipped); assertTrue(plan.entries.isEmpty()); assertTrue(plan.media.isEmpty())
            val videos = store.entries.value.filter { it.notebookId in listOf(importId("dayone:notebook:Journal.json"), importId("dayone:notebook:晓宝贝.json")) }.flatMap { it.videos }
            assertEquals(5, videos.size)
            for (name in videos) {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(File(store.mediaDir, name).path)
                    val frame = retriever.getFrameAtTime(0)
                    assertNotNull("Video frame must decode: $name", frame)
                    frame?.recycle()
                } finally { retriever.release() }
                val player = MediaPlayer()
                try { player.setDataSource(File(store.mediaDir, name).path); player.prepare(); assertTrue(player.duration > 0); assertTrue(player.videoWidth > 0) }
                finally { player.release() }
            }
        }
    }
}
