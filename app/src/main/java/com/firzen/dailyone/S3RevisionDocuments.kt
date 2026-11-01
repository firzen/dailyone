package com.firzen.dailyone

import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** Append-only document versions for S3 gateways which ignore conditional PUT headers.
 * A version key includes its parent and SHA-256. No mutable manifest or last-writer pointer is used.
 */
internal class S3RevisionDocuments(
    private val list: suspend (String) -> Set<String>,
    private val read: suspend (String, Int) -> ByteArray,
    private val write: suspend (String, ByteArray) -> Unit,
) {
    private data class Version(val id: String, val hash: String, val parent: String?, val path: String)
    private val hashPattern = Regex("[0-9a-f]{64}")
    private val snapshots = mutableMapOf<String, Map<String, Version>>()
    private fun token(hash: String) = "\"revision:$hash\""
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun forkId(id: String, hash: String) = UUID.nameUUIDFromBytes("dailyone-conflict:$id:$hash".toByteArray()).toString()
    private fun limit(kind: String) = if (kind == "entries") 2 * 1024 * 1024 else 65536
    private fun versionRoot(kind: String) = ".versions/$kind/"
    // JSONObject iteration order differs across Android/JVM implementations. Canonical bytes
    // make retries and conflict materialization identical on every participating device.
    private fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> quote(value)
        is Number -> java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
        is Boolean -> value.toString()
        else -> throw IllegalArgumentException("S3 版本包含无效 JSON 值")
    }
    private fun quote(value: String): String = buildString {
        append('"')
        for (c in value) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 32 || c.isSurrogate()) append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }
    private fun documentId(doc: JSONObject): String = doc.getString("id").also { require(validId(it)) }
    private fun validate(kind: String, raw: String) {
        if (kind == "entries") Entry.parse(raw) else Notebook.parse(raw)
    }
    private suspend fun load(kind: String, version: Version): JSONObject {
        val bytes = read(version.path, limit(kind) + 4096)
        require(digest(bytes) == version.hash) { "S3 历史版本校验失败，已停止同步" }
        val envelope = JSONObject(bytes.toString(Charsets.UTF_8))
        require(envelope.getInt("version") == 1 && envelope.optString("parent").ifEmpty { null } == version.parent) { "S3 历史版本关系不正确" }
        val doc = envelope.getJSONObject("document")
        require(documentId(doc) == version.id) { "S3 历史版本编号与文件不一致" }
        validate(kind, doc.toString())
        return doc
    }
    private suspend fun publish(kind: String, doc: JSONObject, parent: String?): Version {
        val id = documentId(doc)
        require(canonical(doc).toByteArray().size <= limit(kind)) { "本地文档过大，尚未上传" }
        val bytes = canonical(JSONObject().put("version", 1).put("parent", parent ?: "").put("document", doc)).toByteArray()
        val hash = digest(bytes)
        val path = "${versionRoot(kind)}$id/${parent ?: "root"}.$hash.json"
        write(path, bytes)
        check(read(path, limit(kind) + 4096).contentEquals(bytes)) { "S3 版本写入验证失败，本地修改已保留" }
        return Version(id, hash, parent, path)
    }
    private suspend fun index(kind: String): MutableMap<String, Version> {
        val root = versionRoot(kind)
        val result = linkedMapOf<String, Version>()
        for (path in list(root)) {
            val parts = path.removePrefix(root).split('/')
            if (!path.startsWith(root) || parts.size != 2 || !validId(parts[0])) continue
            val name = parts[1].split('.')
            require(name.size == 3 && name[2] == "json" && (name[0] == "root" || hashPattern.matches(name[0])) && hashPattern.matches(name[1])) { "S3 历史版本文件名不正确" }
            val v = Version(parts[0], name[1], name[0].takeUnless { it == "root" }, path)
            require(result.put(v.hash, v) == null) { "S3 历史版本存在重复编号" }
        }
        return result
    }
    private fun heads(versions: Map<String, Version>): List<Version> {
        val consumed = mutableSetOf<String>()
        for (v in versions.values) {
            val parent = v.parent ?: continue
            val previous = versions[parent] ?: throw IOException("S3 历史版本尚未完整列出，请稍后重试")
            require(v.id == previous.id || v.id == forkId(previous.id, previous.hash)) { "S3 历史版本跨日记引用不正确" }
            consumed += parent
        }
        val resolved = mutableSetOf<String>()
        for (v in versions.values) {
            var current: Version? = v
            val path = linkedSetOf<String>()
            while (current != null && current.hash !in resolved) {
                require(path.add(current.hash)) { "S3 历史版本存在循环引用" }
                current = current.parent?.let { versions.getValue(it) }
            }
            resolved.addAll(path)
        }
        return versions.values.filter { it.hash !in consumed }
    }
    suspend fun listDocuments(kind: String): Map<String, String> {
        val versions = index(kind)
        // Preserve existing mutable-format documents as immutable roots. Never replace or delete them.
        for (path in list("$kind/")) {
            val file = path.removePrefix("$kind/")
            if (!file.endsWith(".json") || !validId(file.removeSuffix(".json"))) continue
            val bytes = read(path, limit(kind))
            val doc = JSONObject(bytes.toString(Charsets.UTF_8)); val id = documentId(doc)
            require(id == file.removeSuffix(".json")) { "S3 原有文档编号不一致" }
            validate(kind, doc.toString())
            val envelope = canonical(JSONObject().put("version", 1).put("parent", "").put("document", doc)).toByteArray()
            val hash = digest(envelope)
            if (hash !in versions) versions[hash] = publish(kind, doc, null)
        }
        // Materialize every concurrent branch as a stable separate record. A fork consumes its
        // source head, so future edits/deletions do not recreate the same conflict repeatedly.
        val groups = heads(versions).groupBy { it.id }
        for ((_, concurrent) in groups) {
            for (v in concurrent.sortedBy { it.hash }.drop(1)) {
                val doc = load(kind, v)
                val id = forkId(v.id, v.hash)
                val fork = if (kind == "entries") {
                    val entry = Entry.parse(doc.toString())
                    JSONObject(entry.copy(id = id, title = entry.title + "（冲突副本）").json())
                } else {
                    val book = Notebook.parse(doc.toString())
                    JSONObject(book.copy(id = id, name = book.name.take(90) + "（冲突副本）").json())
                }
                val created = publish(kind, fork, v.hash)
                versions[created.hash] = created
            }
        }
        val selected = heads(versions).groupBy { it.id }
        require(selected.values.all { it.size == 1 }) { "S3 并发版本尚未整理完成，请重试" }
        val snapshot = selected.mapValues { it.value.single() }
        snapshots[kind] = snapshot
        return snapshot.mapValues { token(it.value.hash) }
    }
    suspend fun getDocument(kind: String, id: String): Pair<String, String> {
        val version = snapshots[kind]?.get(id) ?: run { listDocuments(kind); snapshots[kind]?.get(id) }
            ?: throw IOException("S3 文档已变化，请重新同步")
        return load(kind, version).toString() to token(version.hash)
    }
    suspend fun putDocument(kind: String, raw: String, baseline: String?): String {
        val doc = JSONObject(raw); val id = documentId(doc)
        validate(kind, raw)
        if (kind !in snapshots) listDocuments(kind)
        val latest = snapshots[kind]?.get(id)
        // Legacy HTTP ETags are reconciled by the sync engine through list/get before uploading.
        // An old or unknown baseline must never be silently accepted as the parent of a new edit.
        require(baseline == null || baseline.startsWith("\"revision:")) { "S3 同步格式已更新，请重新同步以核对版本" }
        if ((latest == null && baseline != null) || (latest != null && token(latest.hash) != baseline)) {
            throw IOException("云端记录刚被修改，请再次同步；本地修改已保留")
        }
        val created = publish(kind, doc, latest?.hash)
        snapshots[kind] = snapshots[kind].orEmpty() + (id to created)
        return token(created.hash)
    }
}
