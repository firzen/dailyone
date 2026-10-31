package com.firzen.dailyone

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface ExportNode {
    val name: String
    fun children(): List<ExportNode>
    fun open(): InputStream
}
class TreeNode(private val context: Context, private val tree: Uri, private val id: String, override val name: String) : ExportNode {
    override fun open(): InputStream = requireNotNull(context.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id)))
    override fun children(): List<ExportNode> = context.contentResolver.query(
        DocumentsContract.buildChildDocumentsUriUsingTree(tree, id),
        arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
    )?.use { c -> buildList { while (c.moveToNext()) add(TreeNode(context, tree, c.getString(0), c.getString(1))) } }.orEmpty()
    companion object { fun root(context: Context, tree: Uri) = TreeNode(context, tree, DocumentsContract.getTreeDocumentId(tree), "export") }
}
class LocalExportNode(private val file: File) : ExportNode {
    override val name get() = file.name
    override fun children() = file.listFiles().orEmpty().map(::LocalExportNode)
    override fun open() = file.inputStream()
}
data class ImportMedia(val source: ExportNode, val name: String, val md5: String?, val size: Long)
data class DayOnePlan(val books: List<Notebook>, val entries: List<Entry>, val media: List<ImportMedia>, val archives: List<ExportNode>, val missing: Int, val skipped: Int) {
    val summary get() = "${books.size} 个日记本，新增 ${entries.size} 篇，跳过已有 ${skipped} 篇；${media.count { !validVideo(it.name) }} 张照片，${media.count { validVideo(it.name) }} 个视频，缺失媒体 ${missing} 个。"
}
fun importId(value: String): String = UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()
fun dayOneId(value: String): String {
    val compact = value.replace("-", "").lowercase()
    require(Regex("[0-9a-f]{32}").matches(compact)) { "无效 Day One 日记编号" }
    return "${compact.take(8)}-${compact.substring(8,12)}-${compact.substring(12,16)}-${compact.substring(16,20)}-${compact.substring(20)}"
}
class DayOneImporter(private val store: JournalStore, private val archiveDir: File) {
    suspend fun repairImportedText(): Int = withContext(Dispatchers.IO) {
        val ids = archiveDir.listFiles().orEmpty().filter { it.isDirectory && File(it, "manifest.json").isFile }.flatMap { directory ->
            directory.listFiles().orEmpty().filter { it.extension == "json" && it.name != "manifest.json" }.flatMap { file ->
                val rows = JSONObject(file.readText()).optJSONArray("entries")
                if (rows == null) emptyList() else (0 until rows.length()).map { dayOneId(rows.getJSONObject(it).getString("uuid")) }
            }
        }.toSet()
        store.cleanImportedDayOneText(ids)
    }
    suspend fun preview(root: ExportNode): DayOnePlan = withContext(Dispatchers.IO) {
        val children = root.children()
        val jsons = children.filter { it.name.endsWith(".json", true) }
        require(jsons.isNotEmpty()) { "请选择包含日记 JSON、photos 和 videos 的导出文件夹" }
        val photos = children.firstOrNull { it.name == "photos" }?.children().orEmpty()
        val videos = children.firstOrNull { it.name == "videos" }?.children().orEmpty()
        val media = linkedMapOf<String, ImportMedia>(); val entries = mutableListOf<Entry>(); val books = mutableListOf<Notebook>()
        val seen = mutableSetOf<String>(); var missing = 0; var skipped = 0
        for (source in jsons) {
            val raw = source.open().bufferedReader().use { it.readText() }
            val doc = JSONObject(raw); val rows = doc.getJSONArray("entries")
            val book = Notebook(importId("dayone:notebook:${source.name}"), source.name.dropLast(5))
            books += book
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i); val id = dayOneId(row.getString("uuid"))
                if (!seen.add(id) || store.get(id) != null) { skipped++; continue }
                val names = mutableListOf<String>(); val videoNames = mutableListOf<String>(); val ordered = mutableListOf<Pair<Int, String>>()
                for ((kind, candidates) in listOf("photos" to photos, "videos" to videos)) {
                    val list = row.optJSONArray(kind) ?: continue
                    for (j in 0 until list.length()) {
                        val item = list.getJSONObject(j); val digest = item.optString("md5").lowercase().takeIf { Regex("[0-9a-f]{32}").matches(it) }
                        val file = digest?.let { hash -> candidates.firstOrNull { it.name.substringBeforeLast('.').equals(hash, true) } }
                        if (file == null) { missing++; continue }
                        val ext = file.name.substringAfterLast('.').lowercase()
                        val name = "${importId("dayone:media:$kind:$digest")}.$ext"
                        require(if (kind == "videos") validVideo(name) else validPhoto(name)) { "不支持的媒体格式：${file.name}" }
                        media[name] = ImportMedia(file, name, digest, item.optLong("fileSize", -1))
                        if (kind == "photos") names += name else videoNames += name
                        ordered += item.optInt("orderInEntry", j) to name
                    }
                }
                val location = row.optJSONObject("location")?.let { o ->
                    if (!o.has("latitude") || !o.has("longitude")) null else GeoPoint(o.getDouble("latitude"), o.getDouble("longitude"),
                        name = o.optString("placeName"), address = o.optString("address"), city = o.optString("localityName"), source = "Day One", nameOrigin = PlaceNameOrigin.CUSTOM)
                }
                // text is Day One's readable Markdown export; richText is retained in the archived JSON.
                val text = cleanDayOneText(row.optString("text"))
                val heading = text.lineSequence().firstOrNull().orEmpty()
                val hasTitle = heading.startsWith("# ")
                entries += Entry(id = id, createdAt = Instant.parse(row.getString("creationDate")).toEpochMilli(),
                    updatedAt = Instant.parse(row.getString("modifiedDate")).toEpochMilli(),
                    title = if (hasTitle) heading.removePrefix("# ") else "", body = if (hasTitle) text.substringAfter('\n', "").trimStart('\n') else text,
                    photos = ordered.sortedBy { it.first }.map { it.second }.filter { it in names }.distinct(),
                    videos = ordered.sortedBy { it.first }.map { it.second }.filter { it in videoNames }.distinct(), mediaOrder = ordered.sortedBy { it.first }.map { it.second }.distinct(),
                    notebookId = book.id, recordedLocation = location)
            }
        }
        DayOnePlan(books, entries, media.values.toList(), jsons, missing, skipped)
    }
    suspend fun execute(plan: DayOnePlan, progress: (Int, Int) -> Unit = { _, _ -> }): Int = withContext(Dispatchers.IO) {
        val archive = File(archiveDir, "dayone-${System.currentTimeMillis()}").apply { mkdirs() }
        // Keep the original metadata and an import manifest for auditing or removal of this batch.
        plan.archives.forEach { source -> source.open().use { input -> File(archive, source.name).outputStream().use { input.copyTo(it) } } }
        File(archive, "manifest.json").writeText(JSONObject().put("entryIds", org.json.JSONArray(plan.entries.map { it.id })).put("summary", plan.summary).toString())
        val added = mutableListOf<File>()
        try {
            plan.media.forEachIndexed { index, item ->
                val target = File(store.mediaDir, item.name)
                if (!target.exists()) {
                    val temp = File(store.mediaDir, "${item.name}.part")
                    try {
                        val md = MessageDigest.getInstance("MD5")
                        item.source.open().use { input -> temp.outputStream().use { output ->
                            val buffer = ByteArray(65536)
                            while (true) { val count = input.read(buffer); if (count < 0) break; md.update(buffer, 0, count); output.write(buffer, 0, count) }
                        } }
                        require(temp.length() > 0) { "媒体文件为空：${item.source.name}" }
                        require(item.md5 == null || md.digest().joinToString("") { "%02x".format(it) } == item.md5) { "媒体校验失败：${item.source.name}" }
                        check(temp.renameTo(target)) { "媒体保存失败" }; added += target
                    } finally { temp.delete() }
                }
                progress(index + 1, plan.media.size)
            }
            store.importRecords(plan.books, plan.entries)
        } catch (e: Exception) { added.forEach { it.delete() }; throw e }
    }
}
