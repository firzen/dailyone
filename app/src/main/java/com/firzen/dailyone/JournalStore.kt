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
import java.security.MessageDigest
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class JournalStore(context: Context, databaseName: String = "journal.db", photoDirectoryName: String = "photos") : SQLiteOpenHelper(context, databaseName, null, 7) {
    private val appContext = context.applicationContext
    val mediaDir = File(context.filesDir, photoDirectoryName).apply { mkdirs() }
    val notebooks = MutableStateFlow<List<Notebook>>(emptyList())
    val entries = MutableStateFlow<List<Entry>>(emptyList())
    val scanHistoryPending = MutableStateFlow(false)
    private var photoGeneration = 0L
    private var referencedPhotos = emptySet<String>()
    private fun updateEntries(records: List<Entry>) {
        val names = records.flatMap { it.photos }.toSet()
        if (names != referencedPhotos) { referencedPhotos = names; photoGeneration++ }
        entries.value = records
    }
    val lock = Mutex()
    val syncLock = Mutex()
    val editingId = java.util.concurrent.atomic.AtomicReference<String?>(null)
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE entries (id TEXT PRIMARY KEY, document TEXT NOT NULL, dirty INTEGER NOT NULL, etag TEXT, candidate INTEGER NOT NULL DEFAULT 0, scan_day TEXT)")
        createScanTable(db)
        createPhotoSources(db)
        createScanHistory(db)
        createScanBatches(db)
        createNotebooks(db)
        db.execSQL("CREATE TABLE sync_state (name TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }
    private fun createNotebooks(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS notebooks (id TEXT PRIMARY KEY, document TEXT NOT NULL, dirty INTEGER NOT NULL, etag TEXT)")
        val book = Notebook(DEFAULT_NOTEBOOK, "默认日记本", updatedAt = 0)
        db.insertWithOnConflict("notebooks", null, ContentValues().apply {
            put("id", book.id); put("document", book.json()); put("dirty", 1)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }
    fun refreshNotebooks() {
        notebooks.value = readableDatabase.rawQuery("SELECT document,dirty,etag FROM notebooks", null).use { c ->
            buildList { while (c.moveToNext()) add(Notebook.parse(c.getString(0), c.getString(2)).copy(dirty = c.getInt(1) == 1)) }
        }.sortedBy { it.name }
    }
    fun writeNotebook(book: Notebook) {
        check(writableDatabase.insertWithOnConflict("notebooks", null, ContentValues().apply {
            put("id", book.id); put("document", book.json()); put("dirty", if (book.dirty) 1 else 0); put("etag", book.etag)
        }, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "日记本保存失败" }
        refreshNotebooks()
    }
    suspend fun saveNotebook(book: Notebook) = lock.withLock { withContext(Dispatchers.IO) { writeNotebook(book) } }
    suspend fun cleanImportedDayOneText(ids: Set<String>): Int = lock.withLock { withContext(Dispatchers.IO) {
        val changed = entries.value.filter { it.id in ids && !it.deleted }.mapNotNull { entry ->
            val body = cleanDayOneText(entry.body); val title = cleanDayOneText(entry.title)
            if (body == entry.body && title == entry.title) null else entry.copy(body = body, title = title, dirty = true)
        }
        if (changed.isNotEmpty()) transactionWrite(changed)
        changed.size
    } }
    suspend fun importRecords(books: List<Notebook>, records: List<Entry>): Int = lock.withLock { withContext(Dispatchers.IO) {
        val fresh = records.filter { get(it.id) == null }
        writableDatabase.beginTransaction()
        try {
            books.filter { b -> notebooks.value.none { it.id == b.id } }.forEach(::writeNotebook)
            fresh.forEach(::persist)
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction(); refreshNotebooks(); refresh() }
        fresh.size
    } }
    private fun createPhotoSources(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS photo_replacements (old_name TEXT PRIMARY KEY, new_name TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS photo_sources (photo_name TEXT PRIMARY KEY, source_uri TEXT NOT NULL, latitude REAL, longitude REAL)")
    }
    private fun createScanTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS scanned_media (source_key TEXT PRIMARY KEY, entry_id TEXT, photo_name TEXT)")
    }
    private fun createScanHistory(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS scan_history (sha256 TEXT PRIMARY KEY, dirty INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS scan_source_hashes (source_key TEXT PRIMARY KEY, sha256 TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS photo_fingerprints (photo_name TEXT PRIMARY KEY, size INTEGER NOT NULL, modified INTEGER NOT NULL, sha256 TEXT NOT NULL)")
    }
    private fun createScanBatches(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS scan_batches (id TEXT PRIMARY KEY, document TEXT NOT NULL, dirty INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS scan_history_pending ON scan_history(dirty)")
        db.execSQL("CREATE INDEX IF NOT EXISTS scan_batches_pending ON scan_batches(dirty)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE entries ADD COLUMN candidate INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE entries ADD COLUMN scan_day TEXT")
            createScanTable(db)
        }
        if (oldVersion < 3) createPhotoSources(db)
        if (oldVersion < 4) createNotebooks(db)
        if (oldVersion < 5) db.execSQL("CREATE TABLE IF NOT EXISTS sync_state (name TEXT PRIMARY KEY, value TEXT NOT NULL)")
        if (oldVersion < 6) createScanHistory(db)
        if (oldVersion < 7) createScanBatches(db)
    }
    suspend fun load() = lock.withLock { withContext(Dispatchers.IO) { refreshNotebooks(); refresh() } }
    private fun read(): List<Entry> = readableDatabase.rawQuery("SELECT document, dirty, etag, candidate, scan_day FROM entries", null).use { c ->
        buildList { while (c.moveToNext()) add(Entry.parse(c.getString(0), c.getString(2), c.getInt(3) == 1, c.getString(4)).copy(dirty = c.getInt(1) == 1)) }
    }
    private fun refresh() { updateEntries(read().sortedByDescending { it.createdAt }); refreshScanPending() }
    private fun refreshScanPending() {
        scanHistoryPending.value = readableDatabase.rawQuery("SELECT 1 FROM scan_history WHERE dirty=1 UNION ALL SELECT 1 FROM scan_batches WHERE dirty=1 LIMIT 1", null).use { it.moveToFirst() }
    }
    private fun rememberHash(hash: String, dirty: Boolean): Boolean {
        require(validScanHash(hash))
        val inserted = writableDatabase.insertWithOnConflict("scan_history", null, ContentValues().apply {
            put("sha256", hash); put("dirty", if (dirty) 1 else 0)
        }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        return inserted
    }
    internal suspend fun scanHistoryHashes(pendingOnly: Boolean = false): Set<String> = lock.withLock { withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT sha256 FROM scan_history" + if (pendingOnly) " WHERE dirty=1" else "", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }
    } }
    internal suspend fun acceptScanHash(hash: String) = lock.withLock { withContext(Dispatchers.IO) {
        if (rememberHash(hash, false)) photoGeneration++
        writableDatabase.execSQL("UPDATE scan_history SET dirty=0 WHERE sha256=?", arrayOf(hash))
        refreshScanPending()
    } }
    internal suspend fun markScanHashUploaded(hash: String) = acceptScanHash(hash)
    internal suspend fun backfillScanSource(key: String, uri: Uri) {
        val missing = lock.withLock { withContext(Dispatchers.IO) {
            !readableDatabase.rawQuery("SELECT 1 FROM scan_source_hashes WHERE source_key=?", arrayOf(key)).use { it.moveToFirst() }
        } }
        if (!missing) return
        val hash = originalPhotoDigest(uri)
        lock.withLock { withContext(Dispatchers.IO) {
            writableDatabase.beginTransaction()
            var inserted = false
            try {
                inserted = rememberHash(hash, true)
                writableDatabase.execSQL("INSERT OR IGNORE INTO scan_source_hashes(source_key,sha256) VALUES(?,?)", arrayOf(key, hash))
                writableDatabase.setTransactionSuccessful()
            } finally { writableDatabase.endTransaction() }
            if (inserted) photoGeneration++
            refreshScanPending()
        } }
    }
    fun get(id: String): Entry? = readableDatabase.rawQuery(
        "SELECT document, dirty, etag, candidate, scan_day FROM entries WHERE id = ?", arrayOf(id)
    ).use { c -> if (c.moveToFirst()) Entry.parse(c.getString(0), c.getString(2), c.getInt(3) == 1, c.getString(4)).copy(dirty = c.getInt(1) == 1) else null }
    private fun persist(entry: Entry) {
        val v = ContentValues().apply {
            put("id", entry.id); put("document", entry.json()); put("dirty", if (entry.dirty) 1 else 0)
            put("etag", entry.etag)
            put("candidate", if (entry.candidate) 1 else 0); put("scan_day", entry.scanDay)
        }
        check(writableDatabase.insertWithOnConflict("entries", null, v, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "日记保存失败" }
    }
    private fun publish(entry: Entry) {
        updateEntries((entries.value.filterNot { it.id == entry.id } + entry).sortedByDescending { it.createdAt })
    }
    fun write(entry: Entry) { persist(entry); publish(entry) }
    suspend fun scanned(key: String): Boolean = lock.withLock { withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT 1 FROM scanned_media WHERE source_key=?", arrayOf(key)).use { it.moveToFirst() }
    } }
    private fun recordScanLocked(key: String, timestamp: Long?, photo: String?, hash: String? = null, editingId: () -> String?): Boolean {
        if (readableDatabase.rawQuery("SELECT 1 FROM scanned_media WHERE source_key=?", arrayOf(key)).use { it.moveToFirst() }) return false
        val day = timestamp?.let(::scanDay) ?: "unknown"
        val candidate = if (photo == null) null else {
            val current = entries.value.firstOrNull { it.candidate && !it.deleted && it.scanDay == day && it.id != editingId() }
                ?: Entry(createdAt = timestamp ?: 0L, candidate = true, scanDay = day, dirty = false)
            current.copy(photos = current.photos + photo, updatedAt = System.currentTimeMillis(),
                photoLocations = knownPhotoLocation(photo)?.let { current.photoLocations + (photo to it) } ?: current.photoLocations)
        }
        var insertedHash = false
        writableDatabase.beginTransaction()
        try {
            if (candidate != null) persist(candidate)
            val row = ContentValues().apply { put("source_key", key); put("entry_id", candidate?.id); put("photo_name", photo) }
            writableDatabase.insertOrThrow("scanned_media", null, row)
            if (hash != null) {
                insertedHash = rememberHash(hash, true)
                writableDatabase.execSQL("INSERT OR REPLACE INTO scan_source_hashes(source_key,sha256) VALUES(?,?)", arrayOf(key, hash))
            }
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
        if (insertedHash) photoGeneration++
        refreshScanPending()
        if (candidate != null) publish(candidate)
        return true
    }
    suspend fun recordScan(key: String, timestamp: Long?, photo: String?, editingId: () -> String?) = lock.withLock {
        withContext(Dispatchers.IO) { recordScanLocked(key, timestamp, photo, editingId = editingId); Unit }
    }
    private fun cacheFingerprint(photo: PhotoFingerprint) {
        val row = ContentValues().apply {
            put("photo_name", photo.name); put("size", photo.size); put("modified", photo.modified); put("sha256", photo.hash)
        }
        check(writableDatabase.insertWithOnConflict("photo_fingerprints", null, row, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "图片指纹保存失败" }
    }
    private fun fingerprint(file: File, cached: PhotoFingerprint?): PhotoFingerprint {
        if (cached != null && cached.matches(file)) return cached
        val size = file.length(); val modified = file.lastModified()
        val hash = fileSha256(file)
        check(file.isFile && size == file.length() && modified == file.lastModified()) { "图片正在变化，请稍后重新扫描" }
        return PhotoFingerprint(file.name, size, modified, hash)
    }
    internal suspend fun photoScanIndex(stopped: () -> Boolean = { false },
        progress: (Int, Int) -> Unit = { _, _ -> }): PhotoScanIndex? = withContext(Dispatchers.IO) {
        val snapshot = lock.withLock {
            val cached = readableDatabase.rawQuery("SELECT photo_name,size,modified,sha256 FROM photo_fingerprints", null).use { cursor ->
                buildMap { while (cursor.moveToNext()) {
                    val photo = PhotoFingerprint(cursor.getString(0), cursor.getLong(1), cursor.getLong(2), cursor.getString(3))
                    put(photo.name, photo)
                } }
            }
            val previous = readableDatabase.rawQuery("SELECT photo_name FROM scanned_media WHERE photo_name IS NOT NULL", null).use { c ->
                buildSet { while (c.moveToNext()) add(c.getString(0)) }
            }
            Triple(photoGeneration, referencedPhotos + previous, cached)
        }
        val handled = scanHistoryHashes().toMutableSet()
        var expectedGeneration = snapshot.first
        var stale = false
        var done = 0
        progress(done, snapshot.second.size)
        for (name in snapshot.second) {
            coroutineContext.ensureActive()
            if (stopped()) return@withContext null
            val file = File(mediaDir, name)
            if (file.isFile && file.length() > 0) {
                val photo = fingerprint(file, snapshot.third[name])
                if (photo != snapshot.third[name] || photo.hash !in handled) lock.withLock {
                    if (photoGeneration != expectedGeneration) stale = true
                    var added = false
                    writableDatabase.beginTransaction()
                    try {
                        cacheFingerprint(photo)
                        added = rememberHash(photo.hash, true)
                        writableDatabase.setTransactionSuccessful()
                    } finally { writableDatabase.endTransaction() }
                    if (added) photoGeneration++
                    expectedGeneration = photoGeneration
                    handled.add(photo.hash)
                    refreshScanPending()
                }
            }
            // Every completed file is durable before a stop, cancellation or next file read.
            progress(++done, snapshot.second.size)
        }
        lock.withLock {
            if (photoGeneration != expectedGeneration) stale = true
            PhotoScanIndex(if (stale) snapshot.first else photoGeneration, emptyList(), handled)
        }
    }
    internal suspend fun knownScanBatches(): Set<String> = lock.withLock { withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT id FROM scan_batches WHERE dirty=0", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }
    } }
    internal suspend fun pendingScanBatches(): List<ScanHistoryBatch> = lock.withLock { withContext(Dispatchers.IO) {
        fun pending() = readableDatabase.rawQuery("SELECT id,document FROM scan_batches WHERE dirty=1 ORDER BY id", null).use { c ->
            buildList { while (c.moveToNext()) add(parseScanBatch(c.getString(0), c.getString(1))) }
        }
        val existing = pending()
        val assigned = existing.flatMap { it.hashes }.toSet()
        val fresh = readableDatabase.rawQuery("SELECT sha256 FROM scan_history WHERE dirty=1 ORDER BY sha256", null).use { c ->
            buildList { while (c.moveToNext()) if (c.getString(0) !in assigned) add(c.getString(0)) }
        }
        val batches = fresh.chunked(SCAN_BATCH_SIZE).map(::newScanBatch)
        writableDatabase.beginTransaction()
        try {
            batches.forEach { batch ->
                writableDatabase.insertOrThrow("scan_batches", null, ContentValues().apply {
                    put("id", batch.id); put("document", batch.document); put("dirty", 1)
                })
            }
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
        refreshScanPending()
        existing + batches
    } }
    internal suspend fun acceptScanBatch(id: String, document: String) {
        val batch = parseScanBatch(id, document)
        lock.withLock { withContext(Dispatchers.IO) {
            var added = false
            writableDatabase.beginTransaction()
            try {
                for (hash in batch.hashes) {
                    if (rememberHash(hash, false)) added = true
                    writableDatabase.execSQL("UPDATE scan_history SET dirty=0 WHERE sha256=?", arrayOf(hash))
                }
                writableDatabase.insertWithOnConflict("scan_batches", null, ContentValues().apply {
                    put("id", id); put("document", document); put("dirty", 0)
                }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "去重批次保存失败" } }
                writableDatabase.setTransactionSuccessful()
            } finally { writableDatabase.endTransaction() }
            if (added) photoGeneration++
            refreshScanPending()
        } }
    }
    internal suspend fun importedPhotoFingerprint(name: String): PhotoFingerprint = withContext(Dispatchers.IO) {
        val photo = fingerprint(File(mediaDir, name), null)
        lock.withLock { cacheFingerprint(photo) }
        photo
    }
    suspend fun originalPhotoDigest(uri: Uri): String = withContext(Dispatchers.IO) {
        appContext.contentResolver.openInputStream(originalPhotoUri(appContext, uri)).use { input ->
            requireNotNull(input) { "无法读取原图" }
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer); if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
    internal suspend fun recordScanContent(key: String, hash: String, index: PhotoScanIndex,
        timestamp: Long? = null, photo: String? = null, complete: Boolean = false, editingId: () -> String?): ScanContentResult = lock.withLock { withContext(Dispatchers.IO) {
        fun result(action: ScanContentAction) = ScanContentResult(action, photoGeneration)
        if (readableDatabase.rawQuery("SELECT 1 FROM scanned_media WHERE source_key=?", arrayOf(key)).use { it.moveToFirst() }) return@withContext result(ScanContentAction.SKIPPED)
        if (index.generation != photoGeneration) return@withContext result(ScanContentAction.STALE)
        if (index.contains(hash)) {
            val row = ContentValues().apply { put("source_key", key) }
            writableDatabase.beginTransaction()
            try {
                writableDatabase.insertOrThrow("scanned_media", null, row)
                writableDatabase.execSQL("INSERT OR REPLACE INTO scan_source_hashes(source_key,sha256) VALUES(?,?)", arrayOf(key, hash))
                writableDatabase.setTransactionSuccessful()
            } finally { writableDatabase.endTransaction() }
            return@withContext result(ScanContentAction.SKIPPED)
        }
        if (!complete) return@withContext result(ScanContentAction.UNKNOWN)
        recordScanLocked(key, timestamp, photo, hash, editingId)
        result(if (photo == null) ScanContentAction.RECORDED else ScanContentAction.ADDED)
    } }
    suspend fun review(id: String, keep: Boolean, selected: List<String>? = null): Pair<Entry, Entry>? = lock.withLock {
        withContext(Dispatchers.IO) {
            val before = get(id)?.takeIf { it.candidate && !it.deleted } ?: return@withContext null
            val next = reviewCandidate(before, keep, selected)
            write(next)
            before to next
        }
    }
    suspend fun restoreIfUnchanged(before: Entry, after: Entry): Boolean = restoreBatchIfUnchanged(listOf(before to after))
    suspend fun restoreBatchIfUnchanged(actions: List<Pair<Entry, Entry>>): Boolean = lock.withLock { withContext(Dispatchers.IO) {
        if (actions.any { get(it.second.id) != it.second }) return@withContext false
        transactionWrite(actions.map { it.first }); true
    } }
    suspend fun batch(ids: Set<String>, merge: Boolean, notebookId: String? = null): List<Pair<Entry, Entry>> = lock.withLock { withContext(Dispatchers.IO) {
        val selected = ids.mapNotNull { get(it)?.takeUnless { entry -> entry.deleted } }
        require(selected.size == ids.size) { "所选日记已变化，请重新选择" }
        val action = journalBatch(selected, merge, targetNotebook = notebookId)
        transactionWrite(action.map { it.second }); action
    } }
    suspend fun split(id: String, selected: Set<String>): List<Pair<Entry, Entry>> = lock.withLock { withContext(Dispatchers.IO) {
        val before = requireNotNull(get(id)) { "日记已变化，请重新打开" }
        val (original, created) = journalSplit(before, selected)
        transactionWrite(listOf(original, created))
        // A tombstone is the undo baseline for the newly created synced record.
        listOf(before to original, created.copy(deleted = true) to created)
    } }
    private fun transactionWrite(records: List<Entry>) {
        writableDatabase.beginTransaction()
        try {
            records.forEach(::persist)
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
        val replaced = records.associateBy { it.id }
        updateEntries((entries.value.filterNot { it.id in replaced } + records).sortedByDescending { it.createdAt })
    }
    suspend fun save(entry: Entry) = lock.withLock { withContext(Dispatchers.IO) {
        val resolved = resolvePhotos(entry)
        val latest = get(entry.id)
        val metadata = (latest?.photoLocations.orEmpty() + resolved.photoLocations).mapValues { (name, point) ->
            val stored = latest?.photoLocations?.get(name)
            if (stored != null && point.sameCoordinates(stored)) stored else point
        }
        val manual = resolved.recordedLocation?.let { point ->
            val stored = latest?.recordedLocation
            val automaticSnapshot = point.nameOrigin == PlaceNameOrigin.LEGACY || point.nameOrigin == PlaceNameOrigin.AUTO
            val unchangedCustomName = point.nameOrigin == PlaceNameOrigin.CUSTOM && stored?.nameOrigin == PlaceNameOrigin.CUSTOM && point.name == stored.name && point.poiId == stored.poiId
            if (stored != null && point.sameCoordinates(stored) && (automaticSnapshot && stored.nameOrigin != PlaceNameOrigin.LEGACY || unchangedCustomName || point.name.isBlank() && point.address.isBlank())) stored else point
        }
        write(resolved.copy(photoLocations = metadata.filterKeys { it in resolved.photos }, recordedLocation = manual, etag = latest?.etag ?: resolved.etag))
    } }
    /** Called under the store lock, before any remote operation. Binding and reset commit together. */
    fun bindSyncTarget(target: String) {
        val current = readableDatabase.rawQuery("SELECT value FROM sync_state WHERE name='target'", null).use { if (it.moveToFirst()) it.getString(0) else null }
        if (current == target) return
        writableDatabase.beginTransaction()
        try {
            writableDatabase.execSQL("UPDATE entries SET dirty = 1, etag = NULL WHERE candidate = 0")
            writableDatabase.execSQL("UPDATE notebooks SET dirty = 1, etag = NULL")
            writableDatabase.execSQL("UPDATE scan_history SET dirty = 1")
            writableDatabase.execSQL("UPDATE scan_batches SET dirty = 1")
            writableDatabase.execSQL("INSERT OR REPLACE INTO sync_state(name,value) VALUES('target',?)", arrayOf(target))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
        refreshNotebooks(); refresh()
    }
    suspend fun resetSyncBaseline() = lock.withLock { withContext(Dispatchers.IO) {
        writableDatabase.execSQL("UPDATE entries SET dirty = 1, etag = NULL")
        writableDatabase.execSQL("UPDATE notebooks SET dirty = 1, etag = NULL")
            writableDatabase.execSQL("UPDATE scan_history SET dirty = 1")
            writableDatabase.execSQL("UPDATE scan_batches SET dirty = 1"); refreshNotebooks()
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
        val location = readPhotoLocation(appContext, uri)
        val mime = appContext.contentResolver.getType(uri)
        val extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime ?: "")?.lowercase()
            ?: uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()?.takeIf { Regex("[a-z0-9]{1,10}").matches(it) } ?: "img"
        val name = "${UUID.randomUUID()}.$extension"
        try {
            appContext.contentResolver.openInputStream(originalPhotoUri(appContext, uri)).use { input ->
                requireNotNull(input) { "无法读取原图" }
                File(mediaDir, name).outputStream().use { output -> input.copyTo(output) }
            }
            require(File(mediaDir, name).length() > 0) { "原图内容为空" }
            val source = canonicalPhotoUri(appContext, uri)
            lock.withLock {
                val row = ContentValues().apply {
                    put("photo_name", name); put("source_uri", source.toString())
                    location?.let { put("latitude", it.latitude); put("longitude", it.longitude) }
                }
                writableDatabase.insertOrThrow("photo_sources", null, row)
            }
        } catch (e: Exception) { File(mediaDir, name).delete(); throw e }
        name
    }
    fun knownPhotoLocation(name: String): GeoPoint? = readableDatabase.rawQuery(
        "SELECT latitude,longitude FROM photo_sources WHERE photo_name=?", arrayOf(name)
    ).use { c -> if (c.moveToFirst() && !c.isNull(0) && !c.isNull(1)) GeoPoint(c.getDouble(0), c.getDouble(1)) else null }
    suspend fun originalPhotoSources(names: Set<String>): Map<String, String> = lock.withLock { withContext(Dispatchers.IO) {
        val sources = mutableMapOf<String, String>()
        readableDatabase.rawQuery("SELECT photo_name,source_uri FROM photo_sources", null).use { c ->
            while (c.moveToNext()) if (c.getString(0) in names) sources[c.getString(0)] = c.getString(1)
        }
        readableDatabase.rawQuery("SELECT photo_name,source_key FROM scanned_media WHERE photo_name IS NOT NULL", null).use { c ->
            while (c.moveToNext()) if (c.getString(0) in names && c.getString(0) !in sources)
                sources[c.getString(0)] = c.getString(1).substringBeforeLast(':').substringBeforeLast(':')
        }
        sources
    } }
    fun resolvePhotos(entry: Entry): Entry {
        val aliases = mutableMapOf<String, String>()
        readableDatabase.rawQuery("SELECT old_name,new_name FROM photo_replacements", null).use { c ->
            while (c.moveToNext()) aliases[c.getString(0)] = c.getString(1)
        }
        fun resolve(name: String): String {
            var result = name; val seen = mutableSetOf<String>()
            while (seen.add(result)) result = aliases[result] ?: break
            return result
        }
        val photos = entry.photos.map(::resolve).distinct()
        val locations = entry.photoLocations.mapKeys { resolve(it.key) }.toMutableMap()
        photos.forEach { knownPhotoLocation(it)?.let { point -> locations.putIfAbsent(it, point) } }
        return entry.copy(photos = photos, photoLocations = locations.filterKeys { it in photos }, mediaOrder = entry.mediaOrder.map(::resolve).filter { it in photos || it in entry.videos }.distinct())
    }
    suspend fun replacePhotos(replacements: Map<String, String>): Int = lock.withLock { withContext(Dispatchers.IO) {
        val affected = entries.value.filter { !it.deleted && it.photos.any(replacements::containsKey) }
        writableDatabase.beginTransaction()
        try {
            replacements.forEach { (old, new) ->
                require(validPhoto(new) && File(mediaDir, new).length() > 0)
                writableDatabase.insertOrThrow("photo_replacements", null, ContentValues().apply { put("old_name", old); put("new_name", new) })
            }
            affected.forEach { persist(resolvePhotos(it).copy(updatedAt = System.currentTimeMillis(), dirty = !it.candidate)) }
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
        refresh()
        affected.size
    } }
    suspend fun refreshShopLocation(id: String, original: GeoPoint, fresh: GeoPoint): Boolean = lock.withLock { withContext(Dispatchers.IO) {
        require(original.sameCoordinates(fresh)) { "店铺更新不能移动日记坐标" }
        val latest = get(id)?.takeUnless { it.deleted } ?: return@withContext false
        if (latest.location != original) return@withContext false
        val next = if (latest.recordedLocation != null) {
            val protectName = original.nameOrigin == PlaceNameOrigin.CUSTOM || original.nameOrigin == PlaceNameOrigin.LEGACY && original.name.isNotBlank()
            val updated = if (protectName) fresh.copy(name = original.name, nameOrigin = PlaceNameOrigin.CUSTOM, poiId = original.poiId) else fresh
            latest.copy(recordedLocation = updated)
        } else latest.copy(photoLocations = latest.photoLocations.mapValues { (_, point) -> if (point.sameCoordinates(original)) fresh else point })
        if (next == latest) return@withContext false
        write(next.copy(updatedAt = System.currentTimeMillis(), dirty = !latest.candidate)); true
    } }
    suspend fun nameLocation(id: String, point: GeoPoint, named: GeoPoint) = lock.withLock { withContext(Dispatchers.IO) {
        require(point.sameCoordinates(named))
        val entry = get(id)?.takeUnless { it.deleted } ?: return@withContext
        fun resolve(value: GeoPoint): GeoPoint = if (value.sameCoordinates(point) && value.name.isBlank() && value.address.isBlank()) named else value
        val next = entry.copy(recordedLocation = entry.recordedLocation?.let(::resolve),
            photoLocations = entry.photoLocations.mapValues { resolve(it.value) })
        if (next != entry) write(next.copy(updatedAt = System.currentTimeMillis(), dirty = !entry.candidate))
    } }
    suspend fun fillLocations(found: Map<String, GeoPoint>): Int = lock.withLock { withContext(Dispatchers.IO) {
        val changes = entries.value.filterNot { it.deleted }.mapNotNull { entry ->
            val additions = found.filter { (name, _) -> name in entry.photos && name !in entry.photoLocations }
            if (additions.isEmpty()) null else entry.copy(photoLocations = entry.photoLocations + additions,
                updatedAt = System.currentTimeMillis(), dirty = !entry.candidate)
        }
        if (changes.isNotEmpty()) transactionWrite(changes)
        changes.size
    } }
    suspend fun photoDigest(uri: Uri): String = withContext(Dispatchers.IO) { sha256(encodePhoto(uri)) }
    internal fun encodePhoto(uri: Uri): ByteArray {
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
        return try {
            val output = java.io.ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output))
            output.toByteArray()
        } finally { bitmap.recycle() }
    }
}
