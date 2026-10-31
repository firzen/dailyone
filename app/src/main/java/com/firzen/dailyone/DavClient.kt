package com.firzen.dailyone

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.File
import okhttp3.RequestBody.Companion.asRequestBody
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

class DavClient(config: CloudConfig, private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS)
    .callTimeout(60, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()) {
    private val root: HttpUrl
    private val auth = Credentials.basic(config.username, config.password)
    init {
        val base = config.url.trim().trimEnd('/').toHttpUrl()
        require(base.isHttps && base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null) {
            "请使用不含查询参数的 HTTPS WebDAV 地址"
        }
        root = base.newBuilder().addPathSegment("DailyOne").addPathSegment("").build()
    }
    private fun url(path: String) = root.newBuilder().apply {
        path.split('/').filter { it.isNotEmpty() }.forEach { addPathSegment(it) }
        if (path.endsWith('/')) addPathSegment("")
    }.build()
    private fun request(method: String, path: String, data: ByteArray? = null, headers: Map<String, String> = emptyMap()): okhttp3.Response {
        val body = data?.toRequestBody((headers["Content-Type"] ?: "application/octet-stream").toMediaType())
        val b = Request.Builder().url(url(path)).header("Authorization", auth)
        headers.forEach { (k, v) -> b.header(k, v) }
        return client.newCall(b.method(method, body).build()).execute()
    }
    fun ensureCollections() {
        listOf("", "entries/", "photos/", "notebooks/").forEach { path ->
            request("MKCOL", path).use { r ->
                if (r.code !in listOf(201, 405)) fail(r.code)
            }
        }
    }
    fun listEntries(): Map<String, String> = listDocuments("entries")
    fun listNotebooks(): Map<String, String> = listDocuments("notebooks")
    private fun listDocuments(collection: String): Map<String, String> {
        val xml = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:getetag/></d:prop></d:propfind>"""
        val bytes = request("PROPFIND", "$collection/", xml.toByteArray(), mapOf("Depth" to "1", "Content-Type" to "application/xml")).use { r ->
            if (r.code != 207) fail(r.code)
            bounded(r, 8 * 1024 * 1024)
        }
        // Android's DOM implementation does not support Xerces-specific security flags.
        // Decode strictly, reject DTDs before parsing, and parse a character stream so an
        // alternate byte encoding cannot bypass that check. WebDAV XML must be UTF-8.
        val xmlText = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        require(!xmlText.contains("<!DOCTYPE", ignoreCase = true) && !xmlText.contains("<!ENTITY", ignoreCase = true)) {
            "WebDAV XML 不允许包含 DTD 或实体声明"
        }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw org.xml.sax.SAXException("不允许外部 XML 实体") }
        val doc = builder.parse(org.xml.sax.InputSource(java.io.StringReader(xmlText)))
        val responses = doc.getElementsByTagNameNS("DAV:", "response")
        return buildMap {
            for (i in 0 until responses.length) {
                val node = responses.item(i) as org.w3c.dom.Element
                val href = node.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent ?: continue
                val name = URLDecoder.decode(URI(href).rawPath.substringAfterLast('/'), "UTF-8")
                if (!name.endsWith(".json")) continue
                val id = name.removeSuffix(".json")
                if (!validId(id)) continue
                val tag = node.getElementsByTagNameNS("DAV:", "getetag").item(0)?.textContent?.trim()
                require(!tag.isNullOrBlank() && !tag.startsWith("W/")) { "WebDAV 需要支持强 ETag，以安全同步日记" }
                put(id, tag)
            }
        }
    }
    fun getEntry(id: String): Entry = request("GET", "entries/$id.json").use { r ->
        if (!r.isSuccessful) fail(r.code)
        val tag = r.header("ETag")
        require(!tag.isNullOrBlank() && !tag.startsWith("W/")) { "服务器未提供有效 ETag" }
        val entry = Entry.parse(String(bounded(r, 2 * 1024 * 1024), Charsets.UTF_8), tag)
        require(entry.id == id) { "日记编号与文件不一致" }
        entry
    }
    fun putEntry(entry: Entry): String? {
        val condition = entry.etag?.let { mapOf("If-Match" to it) } ?: mapOf("If-None-Match" to "*")
        return request("PUT", "entries/${entry.id}.json", entry.json().toByteArray(Charsets.UTF_8), condition + ("Content-Type" to "application/json; charset=utf-8")).use { r ->
            if (r.code == 412) throw IOException("云端日记刚被修改，请再次同步；本地修改已保留")
            if (!r.isSuccessful) fail(r.code)
            r.header("ETag")?.takeUnless { it.startsWith("W/") }
        }
    }
    fun getNotebook(id: String): Notebook = request("GET", "notebooks/$id.json").use { r ->
        if (!r.isSuccessful) fail(r.code)
        val tag = r.header("ETag")
        require(!tag.isNullOrBlank() && !tag.startsWith("W/")) { "日记本需要强 ETag" }
        Notebook.parse(String(bounded(r, 65536), Charsets.UTF_8), tag).also { require(it.id == id) }
    }
    fun putNotebook(book: Notebook): String {
        val condition = book.etag?.let { mapOf("If-Match" to it) } ?: mapOf("If-None-Match" to "*")
        return request("PUT", "notebooks/${book.id}.json", book.json().toByteArray(Charsets.UTF_8), condition + ("Content-Type" to "application/json; charset=utf-8")).use { r ->
            if (r.code == 412) throw IOException("日记本刚被修改，请再次同步")
            if (!r.isSuccessful) fail(r.code)
            requireNotNull(r.header("ETag")?.takeIf { it.isNotBlank() && !it.startsWith("W/") }) { "日记本写入后未提供强 ETag" }
        }
    }
    fun putPhoto(name: String, data: ByteArray) {
        require(validPhoto(name))
        request("PUT", "photos/$name", data, mapOf("If-None-Match" to "*", "Content-Type" to "image/jpeg")).use { r ->
            if (!r.isSuccessful && r.code != 412) fail(r.code)
        }
    }
    fun putPhoto(name: String, file: File) {
        require(validPhoto(name))
        val mime = when (name.substringAfterLast('.')) {
            "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"
            "mp4", "m4v" -> "video/mp4"; "mov" -> "video/quicktime"; "webm" -> "video/webm"
            "heic", "heif" -> "image/heic"; "gif" -> "image/gif"; "avif" -> "image/avif"
            else -> "application/octet-stream"
        }
        val req = Request.Builder().url(url("photos/$name")).header("Authorization", auth)
            .header("If-None-Match", "*").put(file.asRequestBody(mime.toMediaType())).build()
        client.newCall(req).execute().use { if (!it.isSuccessful && it.code != 412) fail(it.code) }
    }
    fun downloadPhoto(name: String, destination: File) {
        require(validPhoto(name))
        request("GET", "photos/$name").use { response ->
            if (!response.isSuccessful) fail(response.code)
            val body = response.body ?: error("图片内容为空")
            body.byteStream().use { input -> destination.outputStream().use { input.copyTo(it) } }
            check(body.contentLength() < 0 || destination.length() == body.contentLength()) { "原图下载不完整" }
        }
    }
    private fun bounded(response: okhttp3.Response, limit: Int): ByteArray {
        val body = response.body ?: throw IOException("服务器返回了空内容")
        require(body.contentLength() <= limit) { "云端文件过大" }
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        body.byteStream().use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                require(output.size() + count <= limit) { "云端文件过大" }
                output.write(buffer, 0, count)
            }
        }
        val bytes = output.toByteArray()
        require(bytes.size <= limit) { "云端文件过大" }
        return bytes
    }
    private fun fail(code: Int): Nothing = throw IOException(when (code) {
        401, 403 -> "WebDAV 登录失败，请检查账号和权限（$code）"
        301, 302, 307, 308 -> "服务器要求跳转，请填写最终 WebDAV 地址（$code）"
        404, 409 -> "WebDAV 路径不存在，请填写已有目录的地址（$code）"
        else -> "WebDAV 请求失败（$code）"
    })
}
