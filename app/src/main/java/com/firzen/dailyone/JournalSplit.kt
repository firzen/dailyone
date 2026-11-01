package com.firzen.dailyone

/** Moves photo references only; both journals retain the original displayed place and date. */
fun journalSplit(entry: Entry, selected: Set<String>, now: Long = System.currentTimeMillis()): Pair<Entry, Entry> {
    require(!entry.deleted && !entry.candidate) { "请先保留为正式日记再拆分" }
    require(selected.isNotEmpty() && selected.all { it in entry.photos }) { "请选择日记中的图片" }
    val remaining = entry.photos.filterNot { it in selected }
    require(remaining.isNotEmpty() || entry.title.isNotBlank() || entry.body.isNotBlank() || entry.videos.isNotEmpty()) { "请为原日记至少保留文字、图片或视频" }
    val place = entry.location
    val original = entry.copy(photos = remaining, photoLocations = entry.photoLocations.filterKeys { it !in selected },
        mediaOrder = entry.attachments.filterNot { it in selected }, recordedLocation = place, updatedAt = now, dirty = true)
    val created = Entry(createdAt = entry.createdAt, updatedAt = now, notebookId = entry.notebookId,
        photos = entry.photos.filter { it in selected }, mediaOrder = entry.attachments.filter { it in selected },
        photoLocations = entry.photoLocations.filterKeys { it in selected }, recordedLocation = place)
    return original to created
}
