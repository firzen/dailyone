package com.firzen.dailyone

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
fun fileSha256(file: File): String = file.inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024)
    while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
    digest.digest().joinToString("") { "%02x".format(it) }
}
fun canonicalPhotoUri(context: Context, uri: Uri): Uri = if (Build.VERSION.SDK_INT >= 29) {
    runCatching { if (DocumentsContract.isDocumentUri(context, uri)) MediaStore.getMediaUri(context, uri) ?: uri else uri }.getOrDefault(uri)
} else uri
fun originalPhotoUri(context: Context, uri: Uri): Uri {
    val media = canonicalPhotoUri(context, uri)
    return if (Build.VERSION.SDK_INT >= 29 && media.authority == "media" && media.pathSegments.firstOrNull() in listOf("external", "external_primary", "internal") &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED) MediaStore.setRequireOriginal(media) else uri
}
fun readPhotoLocation(context: Context, uri: Uri): GeoPoint? = runCatching {
    context.contentResolver.openInputStream(originalPhotoUri(context, uri)).use { stream ->
        stream?.let { ExifInterface(it).latLong?.let { coordinates -> GeoPoint(coordinates[0], coordinates[1]) } }
    }
}.getOrNull()

data class GeoBackfillProgress(val running: Boolean = false, val checked: Int = 0, val total: Int = 0,
    val filled: Int = 0, val replaced: Int = 0, val remaining: Int = 0)
class GeoBackfill(private val context: Context, private val store: JournalStore,
    private val photoProvider: (() -> List<Uri>)? = null) {
    val stop = AtomicBoolean(false)
    suspend fun run(progress: (GeoBackfillProgress) -> Unit): GeoBackfillProgress = withContext(Dispatchers.IO) {
        val all = store.entries.value.filterNot { it.deleted }.flatMap { it.photos }.toSet()
        val pending = all.toMutableSet()
        var state = GeoBackfillProgress(running = true, total = pending.size)
        progress(state)
        val localLocations = mutableMapOf<String, GeoPoint>()
        for (name in all) {
            if (stop.get()) break
            readPhotoLocation(context, Uri.fromFile(File(store.mediaDir, name)))?.let { localLocations[name] = it }
        }
        if (localLocations.isNotEmpty()) state = state.copy(filled = store.fillLocations(localLocations))
        // Files imported by the original-copy importer already have provenance. Read GPS locally.
        val sources = store.originalPhotoSources(all)
        val expected = pending.filter { File(store.mediaDir, it).exists() }
            .groupBy { fileSha256(File(store.mediaDir, it)) }.toMutableMap()
        val visited = mutableSetOf<String>()
        suspend fun check(uri: Uri) {
            if (!visited.add(canonicalPhotoUri(context, uri).toString())) return
            val rawHash = context.contentResolver.openInputStream(originalPhotoUri(context, uri)).use { input ->
                requireNotNull(input)
                val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
            val exact = expected[rawHash]
            if (exact != null) {
                val location = readPhotoLocation(context, uri)
                if (location != null) state = state.copy(filled = state.filled + store.fillLocations(exact.associateWith { location }))
                expected.remove(rawHash); pending.removeAll(exact.toSet())
                return
            }
            // Reproduce only the historical encoding to verify identity, never as an import format.
            val digest = store.photoDigest(uri)
            val matched = expected[digest] ?: return
            val replacements = matched.associateWith { store.importPhoto(uri) }
            store.replacePhotos(replacements)
            expected.remove(digest); pending.removeAll(matched.toSet())
            state = state.copy(replaced = state.replaced + matched.size)
        }
        for (source in sources.values.distinct()) {
            if (stop.get()) break
            runCatching { check(Uri.parse(source)) }
            state = state.copy(checked = state.checked + 1); progress(state)
        }
        if (!stop.get() && pending.isNotEmpty()) {
            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            fun gallery(): List<Uri> = context.contentResolver.query(collection,
                arrayOf(MediaStore.Images.Media._ID), null, null, null)?.use { cursor ->
                buildList { while (cursor.moveToNext()) add(ContentUris.withAppendedId(collection, cursor.getLong(0))) }
            }.orEmpty()
            val uris = photoProvider?.invoke() ?: gallery()
            state = state.copy(checked = 0, total = uris.size); progress(state)
            for (uri in uris) {
                if (stop.get() || pending.isEmpty()) break
                runCatching { check(uri) }
                state = state.copy(checked = state.checked + 1); progress(state)
            }
        }
        state.copy(running = false, remaining = pending.size).also(progress)
    }
}
