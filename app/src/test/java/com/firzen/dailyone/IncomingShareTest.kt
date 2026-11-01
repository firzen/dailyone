package com.firzen.dailyone

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class IncomingShareTest {
    private val photo = Uri.parse("content://gallery/photo/1")
    private val video = Uri.parse("content://gallery/video/2")

    @Test fun singlePhoto() {
        assertEquals(listOf(photo), sharedMediaUris(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, photo)))
    }
    @Test fun mixedMediaPreservesOrderAndDeduplicatesClipData() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(video, photo))
        intent.clipData = ClipData.newRawUri("media", photo).apply { addItem(ClipData.Item(video)) }
        assertEquals(listOf(video, photo), sharedMediaUris(intent))
    }
    @Test fun supportsClipOnlyGallery() {
        val intent = Intent(Intent.ACTION_SEND).apply { clipData = ClipData.newRawUri("photo", photo) }
        assertEquals(listOf(photo), sharedMediaUris(intent))
    }
    @Test fun rejectsOtherActionsAndPrivateFilePaths() {
        assertTrue(sharedMediaUris(Intent(Intent.ACTION_VIEW).putExtra(Intent.EXTRA_STREAM, photo)).isEmpty())
        assertTrue(sharedMediaUris(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///private/photo.jpg"))).isEmpty())
    }
}
