package com.firzen.dailyone

import java.io.File

internal data class PhotoFingerprint(val name: String, val size: Long, val modified: Long, val hash: String) {
    fun matches(file: File) = file.isFile && file.length() == size && file.lastModified() == modified
}

internal class PhotoScanIndex(var generation: Long, photos: Collection<PhotoFingerprint>, handled: Set<String> = emptySet()) {
    private val handled = (handled + photos.map { it.hash }).toMutableSet()
    fun contains(hash: String) = hash in handled
    fun completed(hash: String, generation: Long) { handled.add(hash); this.generation = generation }
    fun added(photo: PhotoFingerprint, generation: Long) {
        completed(photo.hash, generation)
    }
}

internal enum class ScanContentAction { STALE, UNKNOWN, SKIPPED, ADDED, RECORDED }
internal data class ScanContentResult(val action: ScanContentAction, val generation: Long)

internal fun validScanHash(hash: String) = hash.matches(Regex("[0-9a-f]{64}"))
internal fun scanHistoryJson(hash: String): String {
    require(validScanHash(hash))
    return "{\"version\":1,\"sha256\":\"$hash\"}"
}
internal fun validateScanHistory(raw: String, hash: String) {
    require(validScanHash(hash))
    val json = org.json.JSONObject(raw)
    require(json.getInt("version") == 1 && json.getString("sha256") == hash) { "扫描去重记录内容与编号不一致" }
}

internal const val SCAN_BATCH_SIZE = 512
internal const val SCAN_BATCH_LIMIT = 64 * 1024
internal data class ScanHistoryBatch(val id: String, val document: String, val hashes: List<String>)
internal fun scanBatchDigest(raw: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
internal fun newScanBatch(hashes: Collection<String>): ScanHistoryBatch {
    val sorted = hashes.distinct().sorted()
    require(sorted.isNotEmpty() && sorted.size <= SCAN_BATCH_SIZE && sorted.all(::validScanHash))
    val raw = "{\"version\":1,\"hashes\":[" + sorted.joinToString(",") { "\"$it\"" } + "]}"
    return ScanHistoryBatch(scanBatchDigest(raw), raw, sorted)
}
internal fun parseScanBatch(id: String, raw: String): ScanHistoryBatch {
    require(validScanHash(id) && raw.toByteArray(Charsets.UTF_8).size <= SCAN_BATCH_LIMIT && scanBatchDigest(raw) == id) {
        "扫描去重批次校验失败"
    }
    val json = org.json.JSONObject(raw)
    require(json.getInt("version") == 1) { "不支持的扫描去重批次版本" }
    val array = json.getJSONArray("hashes")
    require(array.length() in 1..SCAN_BATCH_SIZE)
    val hashes = (0 until array.length()).map { array.getString(it) }
    require(hashes.all(::validScanHash) && hashes == hashes.distinct().sorted()) { "扫描去重批次格式错误" }
    return ScanHistoryBatch(id, raw, hashes)
}
