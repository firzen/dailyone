package com.firzen.dailyone

import java.io.File

/** Blocking network operations run on the sync worker's IO dispatcher. */
interface CloudStorage : AutoCloseable {
    val connectionDetail: String? get() = null
    fun observeRequests(observer: (String) -> Unit) {}
    fun ensureCollections()
    fun listEntries(): Map<String, String>
    fun listNotebooks(): Map<String, String>
    fun listPhotos(): Set<String>
    fun getEntry(id: String): Entry
    fun getNotebook(id: String): Notebook
    fun putEntry(entry: Entry): String?
    fun putNotebook(book: Notebook): String
    fun putPhoto(name: String, file: File)
    fun downloadPhoto(name: String, destination: File)
    fun cancelRequests()
    override fun close() = cancelRequests()
}

fun cloudStorage(config: CloudConfig): CloudStorage = when (config.provider) {
    CloudProvider.WEBDAV -> DavClient(config)
    CloudProvider.S3 -> S3Storage(config.s3)
}

internal fun sameJournalContent(local: Entry, remote: Entry): Boolean =
    local.copy(updatedAt = 0).json() == remote.copy(updatedAt = 0).json()
