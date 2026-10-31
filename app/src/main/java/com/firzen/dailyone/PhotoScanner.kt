package com.firzen.dailyone

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

data class ScanPhoto(val uri: Uri, val key: String, val taken: Long?)

interface PersonClassifier : Closeable { fun containsPerson(bitmap: Bitmap): Boolean }

class PersonDetector(context: Context) : PersonClassifier {
    private val detector = ObjectDetector.createFromOptions(context, ObjectDetector.ObjectDetectorOptions.builder()
        .setBaseOptions(BaseOptions.builder().setModelAssetPath("efficientdet_lite0.tflite").build())
        .setRunningMode(RunningMode.IMAGE).setCategoryAllowlist(listOf("person"))
        .setScoreThreshold(.5f).setMaxResults(10).build())
    override fun containsPerson(bitmap: Bitmap): Boolean {
        val image = BitmapImageBuilder(bitmap).build()
        return try { detector.detect(image).detections().isNotEmpty() } finally { image.close() }
    }
    override fun close() = detector.close()
}

class PhotoScanner(private val context: Context, private val store: JournalStore,
    private val photoProvider: (() -> List<ScanPhoto>)? = null,
    private val detectorFactory: () -> PersonClassifier = { PersonDetector(context) }) {
    val stop = AtomicBoolean(false)
    suspend fun run(editingId: () -> String?, progress: (ScanProgress) -> Unit): ScanProgress = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val photos = mutableListOf<ScanPhoto>()
        if (photoProvider != null) photos.addAll(photoProvider.invoke()) else resolver.query(collection, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE, MediaStore.Images.Media.DATE_TAKEN), null, null, "${MediaStore.Images.Media.DATE_TAKEN} DESC")?.use { cursor ->
            while (cursor.moveToNext() && !stop.get()) {
                val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                photos += ScanPhoto(uri, "$uri:${cursor.getLong(1)}:${cursor.getLong(2)}", cursor.getLong(3).takeIf { it > 0 })
            }
        } ?: error("无法读取相册，请重新授予照片权限")
        var state = ScanProgress(running = true, total = photos.size)
        progress(state)
        if (!stop.get() && photos.isNotEmpty()) detectorFactory().use { detector ->
            for (photo in photos) {
                coroutineContext.ensureActive()
                if (stop.get()) break
                if (store.scanned(photo.key)) {
                    state = state.copy(processed = state.processed + 1, skipped = state.skipped + 1); progress(state); continue
                }
                var imported: String? = null
                try {
                    val bitmap = detectionBitmap(photo.uri)
                    val person = try { detector.containsPerson(bitmap) } finally { bitmap.recycle() }
                    if (stop.get()) break
                    val time = if (person) store.photoTime(photo.uri) ?: photo.taken else null
                    if (person) imported = store.importPhoto(photo.uri)
                    store.recordScan(photo.key, time, imported, editingId)
                    imported = null // The checkpoint and candidate now own this file.
                    state = state.copy(processed = state.processed + 1, matched = state.matched + if (person) 1 else 0)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: SecurityException) { throw SecurityException("相册权限已变更，已有候选已保留，请重新授权后继续") }
                catch (_: Exception) { state = state.copy(processed = state.processed + 1, failed = state.failed + 1) }
                finally { imported?.let { java.io.File(store.mediaDir, it).delete() } }
                progress(state)
            }
        }
        state.copy(running = false, stopping = false).also(progress)
    }
    private fun detectionBitmap(uri: Uri): Bitmap {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
        require(options.outWidth > 0 && options.outHeight > 0) { "不支持的图片" }
        var sample = 1
        while (options.outWidth / sample > 960 || options.outHeight / sample > 960) sample *= 2
        val bitmap = context.contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
        } ?: error("无法解码图片")
        val orientation = runCatching { context.contentResolver.openInputStream(uri).use { stream ->
            stream?.let { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) } ?: 1
        } }.getOrDefault(1)
        val matrix = Matrix().apply { when (orientation) {
            2 -> setScale(-1f, 1f); 3 -> setRotate(180f); 4 -> setScale(1f, -1f)
            5 -> { setRotate(90f); postScale(-1f, 1f) }; 6 -> setRotate(90f)
            7 -> { setRotate(-90f); postScale(-1f, 1f) }; 8 -> setRotate(-90f)
        } }
        return if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
    }
}
