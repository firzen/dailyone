package com.firzen.dailyone

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val uuidPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
fun validId(id: String) = uuidPattern.matches(id)
fun validPhoto(name: String) = name.endsWith(".jpg") && validId(name.removeSuffix(".jpg"))

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
) {
    val syncable get() = !candidate
    val isEmpty get() = title.isBlank() && body.isBlank() && photos.isEmpty()
    fun json(): String = JSONObject().put("version", 1).put("id", id)
        .put("createdAt", createdAt).put("updatedAt", updatedAt)
        .put("title", title).put("body", body).put("photos", JSONArray(photos))
        .put("deleted", deleted).toString()

    companion object {
        fun parse(raw: String, etag: String? = null, candidate: Boolean = false, scanDay: String? = null): Entry {
            val o = JSONObject(raw)
            require(o.getInt("version") == 1) { "不支持的日记格式" }
            val id = o.getString("id")
            require(validId(id)) { "无效日记编号" }
            val array = o.getJSONArray("photos")
            val photos = (0 until array.length()).map { array.getString(it) }
            require(photos.all(::validPhoto)) { "无效图片名称" }
            return Entry(id, o.getLong("createdAt"), o.getLong("updatedAt"),
                o.getString("title"), o.getString("body"), photos,
                o.optBoolean("deleted"), dirty = false, etag = etag, candidate = candidate, scanDay = scanDay)
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
