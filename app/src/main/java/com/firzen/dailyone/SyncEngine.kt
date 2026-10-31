package com.firzen.dailyone

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class SyncEngine(private val store: JournalStore) {
    suspend fun sync(config: CloudConfig): Int = store.lock.withLock {
        withContext(Dispatchers.IO) {
            val dav = DavClient(config)
            dav.ensureCollections()
            var conflicts = 0
            for ((id, etag) in dav.listNotebooks()) {
                val local = store.notebooks.value.firstOrNull { it.id == id }
                if (local?.etag == etag) continue
                val remote = dav.getNotebook(id)
                if (local != null && local.dirty && local.updatedAt > 0 && local.name != remote.name) {
                    val copy = local.copy(id = UUID.randomUUID().toString(), name = local.name.take(90) + "（冲突副本）", etag = null)
                    store.writeNotebook(copy)
                    store.entries.value.filter { it.notebookId == id && it.dirty }.forEach { store.write(it.copy(notebookId = copy.id)) }
                    conflicts++
                }
                store.writeNotebook(remote)
            }
            for (book in store.notebooks.value.filter { it.dirty }) {
                store.writeNotebook(book.copy(dirty = false, etag = dav.putNotebook(book)))
            }
            for ((id, etag) in dav.listEntries()) {
                val local = store.get(id)
                if (mergeAction(local, etag) == MergeAction.KEEP_LOCAL) continue
                val remote = dav.getEntry(id)
                val action = mergeAction(local, requireNotNull(remote.etag))
                if (action == MergeAction.KEEP_LOCAL) continue
                if (!remote.deleted) for (name in remote.attachments) {
                    val file = File(store.mediaDir, name)
                    if (!file.exists()) {
                        val temp = File(store.mediaDir, "$name.part")
                        dav.downloadPhoto(name, temp)
                        check(temp.renameTo(file)) { "图片保存失败" }
                    }
                }
                if (action == MergeAction.CONFLICT && local != null) {
                    // A distinct record preserves the local side, including a concurrent deletion.
                    store.write(local.copy(id = UUID.randomUUID().toString(),
                        title = local.title + "（冲突副本）", deleted = false, dirty = true, etag = null))
                    conflicts++
                }
                store.write(remote)
            }
            for (entry in store.entries.value.filter { it.dirty && it.syncable }.sortedBy { it.deleted }) {
                if (!entry.deleted) for (name in entry.attachments) {
                    val photo = File(store.mediaDir, name)
                    check(photo.exists()) { "日记图片缺失：$name" }
                    dav.putPhoto(name, photo)
                }
                val tag = dav.putEntry(entry)
                // Never GET after a successful write to establish a baseline: another device
                // could write in that gap. No returned ETag means retry with conflict protection.
                if (tag != null) store.write(entry.copy(dirty = false, etag = tag))
                else error("服务器写入后未提供强 ETag，本地记录已保留，请检查 WebDAV 兼容性")
            }
            conflicts
        }
    }
}
