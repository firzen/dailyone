package com.firzen.dailyone

import java.time.Instant
import java.time.ZoneId

fun scanDay(timestamp: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate().toString()

fun reviewCandidate(entry: Entry, keep: Boolean, selected: List<String>? = null): Entry {
    require(entry.candidate && !entry.deleted)
    val photos = selected ?: entry.photos
    if (keep) {
        require((photos.isNotEmpty() || entry.title.isNotBlank() || entry.body.isNotBlank()) && photos.distinct().size == photos.size && photos.all { it in entry.photos }) { "请保留日记内容" }
        require(entry.scanDay != "unknown") { "请先在编辑页确认日记日期" }
    }
    return entry.copy(candidate = !keep, scanDay = if (keep) null else entry.scanDay,
        photos = if (keep) photos else entry.photos, deleted = !keep, dirty = keep,
        updatedAt = System.currentTimeMillis(), etag = null)
}

data class ScanProgress(val running: Boolean = false, val stopping: Boolean = false,
    val total: Int = 0, val processed: Int = 0, val matched: Int = 0, val skipped: Int = 0, val failed: Int = 0, val stage: String = "")

fun Entry.withJournalTime(timestamp: Long): Entry = copy(createdAt = timestamp,
    scanDay = if (candidate) com.firzen.dailyone.scanDay(timestamp) else scanDay)
