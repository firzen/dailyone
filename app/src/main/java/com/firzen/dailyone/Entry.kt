package com.firzen.dailyone

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val uuidPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
fun validId(id: String) = uuidPattern.matches(id)
fun validPhoto(name: String) = validId(name.substringBeforeLast('.', "")) && Regex("[a-z0-9]{1,10}").matches(name.substringAfterLast('.', ""))

data class Entry(
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val title: String = "",
    val body: String = "",
    val photos: List<String> = emptyList(),
    val deleted: Boolean = false,
    val dirty: Boolean = true,
    val etag: String? = null,
    val candidate: Boolean = false,
    val scanDay: String? = null,
    val photoLocations: Map<String, GeoPoint> = emptyMap(),
    val recordedLocation: GeoPoint? = null,
    val notebookId: String = DEFAULT_NOTEBOOK,
    val videos: List<String> = emptyList(),
    val mediaOrder: List<String> = emptyList(),
) {
    val location get() = recordedLocation ?: photos.firstNotNullOfOrNull { photoLocations[it] }
    val attachments get() = (mediaOrder.filter { it in photos || it in videos } + photos + videos).distinct()
    val syncable get() = !candidate
    val isEmpty get() = title.isBlank() && body.isBlank() && photos.isEmpty() && videos.isEmpty() && recordedLocation == null
    fun json(): String = JSONObject().put("version", 2).put("id", id)
        .put("createdAt", createdAt).put("updatedAt", updatedAt)
        .put("title", title).put("body", body).put("photos", JSONArray(photos))
        .put("deleted", deleted)
        .put("photoLocations", JSONObject().apply { photos.forEach { name -> photoLocations[name]?.let { put(name, it.json()) } } })
        .put("notebookId", notebookId).put("videos", JSONArray(videos)).put("mediaOrder", JSONArray(if (mediaOrder.isEmpty()) emptyList<String>() else attachments))
        .put("location", recordedLocation?.json()).toString()

    companion object {
        fun parse(raw: String, etag: String? = null, candidate: Boolean = false, scanDay: String? = null): Entry {
            val o = JSONObject(raw)
            require(o.getInt("version") in 1..2) { "不支持的日记格式" }
            val id = o.getString("id")
            require(validId(id)) { "无效日记编号" }
            val array = o.getJSONArray("photos")
            val photos = (0 until array.length()).map { array.getString(it) }
            require(photos.all(::validPhoto)) { "无效图片名称" }
            val notebook = o.optString("notebookId", DEFAULT_NOTEBOOK)
            require(validId(notebook)) { "无效日记本编号" }
            fun names(key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            val videos = names("videos")
            require(videos.all(::validVideo)) { "无效视频名称" }
            val order = names("mediaOrder")
            require(order.all { it in photos || it in videos }) { "无效媒体顺序" }
            val locations = buildMap<String, GeoPoint> {
                val rawLocations = o.optJSONObject("photoLocations")
                photos.forEach { name -> GeoPoint.parse(rawLocations?.optJSONObject(name))?.let { put(name, it) } }
            }
            return Entry(id, o.getLong("createdAt"), o.getLong("updatedAt"),
                o.getString("title"), o.getString("body"), photos,
                o.optBoolean("deleted"), dirty = false, etag = etag, candidate = candidate, scanDay = scanDay, photoLocations = locations, recordedLocation = GeoPoint.parse(o.optJSONObject("location")), notebookId = notebook, videos = videos, mediaOrder = order)
        }
    }
}

enum class MergeAction { KEEP_LOCAL, TAKE_REMOTE, CONFLICT }
fun mergeAction(local: Entry?, remoteEtag: String): MergeAction = when {
    local == null -> MergeAction.TAKE_REMOTE
    local.etag == remoteEtag -> MergeAction.KEEP_LOCAL
    local.dirty -> MergeAction.CONFLICT
    else -> MergeAction.TAKE_REMOTE
}

fun validVideo(name: String) = validPhoto(name) && name.substringAfterLast('.') in listOf("mp4", "m4v", "mov", "3gp", "webm", "mkv")
