package com.firzen.dailyone

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class JournalStore(context: Context) : SQLiteOpenHelper(context, "journal.db", null, 1) {
    private val appContext = context.applicationContext
    val mediaDir = File(context.filesDir, "photos").apply { mkdirs() }
    val entries = MutableStateFlow<List<Entry>>(emptyList())
    val lock = Mutex()
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE entries (id TEXT PRIMARY KEY, document TEXT NOT NULL, dirty INTEGER NOT NULL, etag TEXT)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    suspend fun load() = lock.withLock { withContext(Dispatchers.IO) { refresh() } }
    private fun read(): List<Entry> = readableDatabase.rawQuery("SELECT document, dirty, etag FROM entries", null).use { c ->
        buildList { while (c.moveToNext()) add(Entry.parse(c.getString(0), c.getString(2)).copy(dirty = c.getInt(1) == 1)) }
    }
    private fun refresh() { entries.value = read().sortedByDescending { it.createdAt } }
    fun get(id: String): Entry? = readableDatabase.rawQuery(
        "SELECT document, dirty, etag FROM entries WHERE id = ?", arrayOf(id)
    ).use { c -> if (c.moveToFirst()) Entry.parse(c.getString(0), c.getString(2)).copy(dirty = c.getInt(1) == 1) else null }
    fun write(entry: Entry) {
        val v = ContentValues().apply {
            put("id", entry.id); put("document", entry.json()); put("dirty", if (entry.dirty) 1 else 0)
            put("etag", entry.etag)
        }
        check(writableDatabase.insertWithOnConflict("entries", null, v, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "日记保存失败" }
        entries.value = (entries.value.filterNot { it.id == entry.id } + entry).sortedByDescending { it.createdAt }
    }
    suspend fun save(entry: Entry) = lock.withLock { withContext(Dispatchers.IO) { write(entry) } }
    suspend fun resetSyncBaseline() = lock.withLock { withContext(Dispatchers.IO) {
        writableDatabase.execSQL("UPDATE entries SET dirty = 1, etag = NULL")
        refresh()
    } }
    suspend fun photoTime(uri: Uri): Long? = withContext(Dispatchers.IO) {
        val exifTime = runCatching {
            appContext.contentResolver.openInputStream(uri).use { stream ->
                if (stream == null) null else {
                    val exif = ExifInterface(stream)
                    parsePhotoTime(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL), exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
                        ?: parsePhotoTime(exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED), exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED))
                        ?: parsePhotoTime(exif.getAttribute(ExifInterface.TAG_DATETIME), exif.getAttribute(ExifInterface.TAG_OFFSET_TIME))
                }
            }
        }.getOrNull()
        exifTime ?: runCatching {
            val column = android.provider.MediaStore.Images.ImageColumns.DATE_TAKEN
            appContext.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(column)
                if (cursor.moveToFirst() && index >= 0 && !cursor.isNull(index)) cursor.getLong(index).takeIf { it > 0 } else null
            }
        }.getOrNull()
    }
    suspend fun importPhoto(uri: Uri): String = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        appContext.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取这张图片" }
        val options = BitmapFactory.Options().apply {
            var sample = 1
            while (bounds.outWidth / sample > 2400 || bounds.outHeight / sample > 2400) sample *= 2
            inSampleSize = sample
        }
        val decoded = appContext.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("无法读取这张图片")
        val orientation = appContext.contentResolver.openInputStream(uri).use { stream ->
            if (stream == null) ExifInterface.ORIENTATION_NORMAL
            else ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
        val bitmap = if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (bitmap !== decoded) decoded.recycle()
        val name = "${UUID.randomUUID()}.jpg"
        try { File(mediaDir, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it)) } }
        catch (e: Exception) { File(mediaDir, name).delete(); throw e }
        finally { bitmap.recycle() }
        name
    }
}
