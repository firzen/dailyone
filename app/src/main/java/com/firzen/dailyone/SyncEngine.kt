package com.firzen.dailyone

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class SyncProgress(val running: Boolean = false, val percent: Int = 0, val stage: String = "", val message: String? = null, val waiting: Boolean = false, val paused: Boolean = false, val controlling: Boolean = false)

class SyncEngine(private val store: JournalStore, private val storageFactory: (CloudConfig) -> CloudStorage = ::cloudStorage) {
    suspend fun sync(config: CloudConfig, progress: (SyncProgress) -> Unit = {}): Int = store.syncLock.withLock {
        withContext(Dispatchers.IO) {
            suspend fun <T> local(block: () -> T): T { currentCoroutineContext().ensureActive(); return store.lock.withLock { currentCoroutineContext().ensureActive(); block() } }
            var currentPercent = 0
            fun report(percent: Int, stage: String) { currentPercent = percent; progress(SyncProgress(true, percent, stage)) }
            config.validate()
            local { store.bindSyncTarget(config.targetId()) }
            val storage = storageFactory(config)
            storage.observeRequests { stage -> progress(SyncProgress(true, currentPercent, stage)) }
            val cancellation = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { storage.cancelRequests() }
            }
            try {
                report(0, "连接云存储")
                storage.ensureCollections()
                storage.connectionDetail?.let { report(1, it) }
                val books = storage.listNotebooks()
                val entries = storage.listEntries()
                var conflicts = 0
                var downloaded = 0
                val remoteTotal = (books.size + entries.size).coerceAtLeast(1)
                fun checked() { downloaded++; report(5 + 40 * downloaded / remoteTotal, "检查云端 $downloaded/${books.size + entries.size}") }
                report(5, "检查云端记录")
                for ((id, etag) in books) {
                    currentCoroutineContext().ensureActive()
                    if (local { store.notebooks.value.firstOrNull { it.id == id }?.etag != etag }) {
                        val remote = storage.getNotebook(id)
                        local {
                            val latest = store.notebooks.value.firstOrNull { it.id == id }
                            if (latest != null && latest.dirty && latest.updatedAt > 0 && latest.name != remote.name) {
                                val copy = latest.copy(id = UUID.randomUUID().toString(), name = latest.name.take(90) + "（冲突副本）", etag = null)
                                store.writeNotebook(copy)
                                store.entries.value.filter { it.notebookId == id && it.dirty }.forEach { store.write(it.copy(notebookId = copy.id)) }
                                conflicts++
                            }
                            store.writeNotebook(remote)
                        }
                    }
                    checked()
                }
                for ((id, etag) in entries) {
                    currentCoroutineContext().ensureActive()
                    val shouldRead = local { store.editingId.get() != id && mergeAction(store.get(id), etag) != MergeAction.KEEP_LOCAL }
                    if (shouldRead) {
                        val remote = storage.getEntry(id)
                        if (!remote.deleted) for (name in remote.attachments) {
                            val file = File(store.mediaDir, name)
                            if (!file.exists()) {
                                val temp = File(store.mediaDir, "$name.${UUID.randomUUID()}.part")
                                try {
                                    storage.downloadPhoto(name, temp)
                                    check(temp.renameTo(file)) { "图片保存失败" }
                                } finally { temp.delete() }
                            }
                        }
                        local {
                            val latest = store.get(id)
                            val action = mergeAction(latest, requireNotNull(remote.etag))
                            // An open editor owns its draft. Defer remote replacement until it closes.
                            if (store.editingId.get() != id && action != MergeAction.KEEP_LOCAL) {
                                if (action == MergeAction.CONFLICT && latest != null && !sameJournalContent(latest, remote)) {
                                    store.write(latest.copy(id = UUID.randomUUID().toString(), title = latest.title + "（冲突副本）", deleted = false, dirty = true, etag = null))
                                    conflicts++
                                }
                                store.write(remote)
                            }
                        }
                    }
                    checked()
                }
                val pendingBooks = local { store.notebooks.value.filter { it.dirty } }
                val pendingEntries = local { store.entries.value.filter { it.dirty && it.syncable }.sortedBy { it.deleted } }
                val remotePhotos = if (pendingEntries.any { !it.deleted && it.attachments.isNotEmpty() }) storage.listPhotos().toMutableSet() else mutableSetOf()
                val total = pendingBooks.size + pendingEntries.sumOf { 1 + if (it.deleted) 0 else it.attachments.size }
                var done = 0
                fun uploaded() { done++; report(45 + 54 * done / total.coerceAtLeast(1), "上传 $done/$total") }
                report(45, "准备上传")
                for (book in pendingBooks) {
                    currentCoroutineContext().ensureActive()
                    val tag = storage.putNotebook(book)
                    local {
                        val latest = store.notebooks.value.firstOrNull { it.id == book.id }
                        if (latest != null) store.writeNotebook(latest.copy(dirty = latest != book, etag = tag))
                    }
                    uploaded()
                }
                for (entry in pendingEntries) {
                    currentCoroutineContext().ensureActive()
                    if (!entry.deleted) for (name in entry.attachments) {
                        currentCoroutineContext().ensureActive()
                        if (name in remotePhotos) { uploaded(); continue }
                        val photo = File(store.mediaDir, name)
                        check(photo.exists()) { "日记图片缺失：$name" }
                        storage.putPhoto(name, photo)
                        remotePhotos.add(name)
                        uploaded()
                    }
                    val tag = requireNotNull(storage.putEntry(entry)) { "服务器写入后未提供有效 ETag，本地记录已保留" }
                    local {
                        val latest = store.get(entry.id)
                        // Keep edits made during network I/O dirty, and advance only their baseline.
                        if (latest != null) store.write(latest.copy(dirty = latest != entry, etag = tag))
                    }
                    uploaded()
                }
                report(100, "同步完成")
                conflicts
            } finally { cancellation.cancel(); storage.close() }
        }
    }
}
