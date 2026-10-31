package com.firzen.dailyone

/** Builds an atomic before/after plan; the earliest known journal remains the merged record. */
fun journalBatch(entries: List<Entry>, merge: Boolean, now: Long = System.currentTimeMillis(), targetNotebook: String? = null): List<Pair<Entry, Entry>> {
    require(entries.isNotEmpty() && entries.map { it.id }.distinct().size == entries.size && entries.none { it.deleted }) { "请选择有效日记" }
    if (!merge) return entries.map { it to it.copy(deleted = true, dirty = !it.candidate, updatedAt = now) }
    require(entries.size > 1) { "至少选择两篇日记才能合并" }
    val sorted = entries.sortedWith(compareBy<Entry> { if (it.scanDay == "unknown") Long.MAX_VALUE else it.createdAt }.thenBy { it.id })
    val first = sorted.first()
    val candidate = sorted.all { it.candidate }
    val body = sorted.mapIndexed { index, entry ->
        (if (index == 0) listOf(entry.body) else listOf(entry.title, entry.body)).filter { it.isNotBlank() }.joinToString("\n")
    }.filter { it.isNotBlank() }.joinToString("\n\n")
    require(sorted.map { it.notebookId }.distinct().size == 1 || targetNotebook != null) { "请选择合并后的日记本" }
    val merged = first.copy(body = body, notebookId = targetNotebook ?: first.notebookId, videos = sorted.flatMap { it.videos }.distinct(), mediaOrder = sorted.flatMap { it.attachments }.distinct(), photos = sorted.flatMap { it.photos }.distinct(), candidate = candidate,
        scanDay = if (candidate) first.scanDay else null, updatedAt = now, dirty = !candidate,
        photoLocations = sorted.flatMap { it.photoLocations.entries }.associate { it.key to it.value },
        recordedLocation = sorted.firstNotNullOfOrNull { it.recordedLocation })
    return listOf(first to merged) + sorted.drop(1).map { it to it.copy(deleted = true, dirty = !it.candidate, updatedAt = now) }
}
