package com.firzen.dailyone

import org.json.JSONObject
import java.util.UUID

const val DEFAULT_NOTEBOOK = "00000000-0000-0000-0000-000000000001"
data class Notebook(val id: String = UUID.randomUUID().toString(), val name: String,
    val updatedAt: Long = System.currentTimeMillis(), val dirty: Boolean = true, val etag: String? = null) {
    init { require(validId(id) && name.isNotBlank() && name.length <= 100) { "请输入 1–100 字的日记本名称" } }
    fun json() = JSONObject().put("version", 1).put("id", id).put("name", name).put("updatedAt", updatedAt).toString()
    companion object {
        fun parse(raw: String, etag: String? = null): Notebook {
            val o = JSONObject(raw); require(o.getInt("version") == 1)
            return Notebook(o.getString("id"), o.getString("name"), o.getLong("updatedAt"), false, etag)
        }
    }
}
