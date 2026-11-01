package com.firzen.dailyone

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.putObject
import aws.sdk.kotlin.services.s3.deleteObject
import aws.sdk.kotlin.services.s3.listObjectsV2
import aws.sdk.kotlin.services.s3.listObjects
import aws.sdk.kotlin.services.s3.headObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import aws.sdk.kotlin.services.s3.model.S3Exception
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.content.fromFile
import aws.smithy.kotlin.runtime.content.toFlow
import aws.smithy.kotlin.runtime.content.writeToFile
import aws.smithy.kotlin.runtime.net.url.Url
import aws.smithy.kotlin.runtime.http.response.HttpResponse
import aws.smithy.kotlin.runtime.retries.StandardRetryStrategy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class S3Storage(input: S3Config, suppliedClient: S3Client? = null) : CloudStorage {
    private val config = input.normalized().also { it.validate() }
    private val job = SupervisorJob()
    private var activeOperation = "连接 S3"
    private var observer: (String) -> Unit = {}
    override fun observeRequests(observer: (String) -> Unit) { this.observer = observer }
    private fun stage(action: String, path: String) {
        val type = when {
            path.startsWith(".checks/") -> "兼容性检查对象"
            path.startsWith(".versions/notebooks/") -> "日记本版本"
            path.startsWith(".versions/entries/") -> "日记版本"
            path.startsWith("notebooks/") -> "日记本"
            path.startsWith("entries/") -> "日记"
            path.startsWith("scan-history-batches/") -> "扫描去重批次"
            path.startsWith("scan-history/") -> "扫描去重记录"
            path.startsWith("photos/") -> "媒体"
            else -> "S3 对象"
        }
        activeOperation = action + type
        observer(activeOperation)
        android.util.Log.i("DailyOneS3", "开始：$activeOperation")
    }
    private val client = suppliedClient ?: S3Client {
        region = config.region
        endpointUrl = Url.parse(config.endpoint)
        forcePathStyle = config.pathStyle
        enableAwsChunked = false
        applyS3GatewayCompatibility(config)
        httpClient { connectTimeout = 20.seconds; socketReadTimeout = 40.seconds; socketWriteTimeout = 40.seconds }
        credentialsProvider = StaticCredentialsProvider { accessKeyId = config.accessKey; secretAccessKey = config.secretKey }
        // The persistent worker handles retry. Do not retry an uncertain conditional write blindly.
        retryStrategy = StandardRetryStrategy { maxAttempts = 1 }
    }
    private val revisionMode = config.endpoint.toHttpUrl().host.endsWith(".qiniucs.com")
    internal var conditionChecks: S3ConditionChecks? = null; private set
    override val connectionDetail: String? get() = conditionChecks?.let {
        if (revisionMode) "七牛版本同步 · ${it.description}" else it.description
    }
    private val revisions by lazy { S3RevisionDocuments(
        list = { prefix -> listObjects(prefix).keys },
        read = { path, limit -> readDocument(path, limit).first },
        write = { path, data ->
            stage("上传", path)
            client.putObject { bucket = config.bucket; key = key(path); body = ByteStream.fromBytes(data); contentType = "application/json; charset=utf-8" }
            Unit
        },
    ) }
    private fun key(path: String) = config.prefix + path
    private fun <T> request(block: suspend () -> T): T = runBlocking(job) {
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: S3Exception) {
            android.util.Log.w("DailyOneS3", "失败：$activeOperation（S3Exception）")
            throw IOException(activeOperation + "：" + when (e.sdkErrorMetadata.errorCode) {
                "PreconditionFailed", "ConditionalRequestConflict" -> "云端记录刚被修改，请再次同步；本地修改已保留"
                "AccessDenied", "InvalidAccessKeyId", "SignatureDoesNotMatch" -> "S3 认证失败，请检查访问密钥、Region 和权限"
                "NoSuchBucket" -> "S3 Bucket 不存在，请填写已有 Bucket"
                "PermanentRedirect", "AuthorizationHeaderMalformed" -> "请检查 S3 Endpoint 和 Region"
                else -> "S3 请求失败（${e.sdkErrorMetadata.errorCode ?: "未知错误"}）"
            }, e)
        } catch (e: Exception) {
            if (e is IllegalArgumentException || e is IllegalStateException) throw e
            val timeout = generateSequence<Throwable>(e) { it.cause }.take(16).any {
                it is java.net.SocketTimeoutException || it is java.io.InterruptedIOException
            }
            val reason = if (timeout) "网络超时，将保留本地修改并重试" else if (e is IOException) e.message ?: "连接失败" else "网络请求失败（${e.javaClass.simpleName}）"
            android.util.Log.w("DailyOneS3", "失败：$activeOperation（${if (timeout) "timeout" else e.javaClass.simpleName}）")
            throw IOException("$activeOperation：$reason", e)
        }
    }
    private fun etag(value: String?): String {
        require(!value.isNullOrBlank() && !value.startsWith("W/")) { "S3 必须返回有效 ETag 才能安全同步" }
        return value
    }
    private suspend fun readDocument(path: String, limit: Int): Pair<ByteArray, String> {
        stage("读取", path)
        return client.getObject(GetObjectRequest { bucket = config.bucket; key = key(path) }) { response ->
            require((response.contentLength ?: 0) <= limit) { "云端文件过大" }
            val body = requireNotNull(response.body) { "云端文件内容为空" }
            val output = ByteArrayOutputStream()
            body.toFlow().collect { chunk ->
                require(output.size() + chunk.size <= limit) { "云端文件过大" }; output.write(chunk)
            }
            val bytes = output.toByteArray()
            require(response.contentLength == null || response.contentLength == bytes.size.toLong()) { "云端文件下载不完整" }
            bytes to etag(response.eTag)
        }
    }
    private suspend fun putDocument(path: String, data: ByteArray, baseline: String?): String {
        stage("上传", path)
        val response = client.putObject {
            bucket = config.bucket; key = key(path); body = ByteStream.fromBytes(data); contentType = "application/json; charset=utf-8"
            if (baseline == null) ifNoneMatch = "*" else ifMatch = baseline
        }
        response.eTag?.let { return etag(it) }
        val (actual, tag) = readDocument(path, 2 * 1024 * 1024)
        check(actual.contentEquals(data)) { "云端记录刚被修改，请再次同步；本地修改已保留" }
        return tag
    }
    private suspend fun conditionProbe(create: Boolean): Boolean {
        val path = ".checks/${UUID.randomUUID()}.json"
        var failure: Throwable? = null
        try {
            val original = "{}".toByteArray()
            val first = if (create) putDocument(path, original, null) else {
                stage("上传", path)
                client.putObject { bucket = config.bucket; key = key(path); body = ByteStream.fromBytes(original) }
                readDocument(path, 1024).second
            }
            // Keep the server's ETag syntax/length; only change a single digit/letter.
            val wrong = first.toCharArray()
            val changedIndex = wrong.indexOfFirst { it.isLetterOrDigit() }
            require(changedIndex >= 0) { "S3 返回了无效 ETag" }
            wrong[changedIndex] = if (wrong[changedIndex] == '0') '1' else '0'
            val wrongTag = String(wrong)
            try {
                putDocument(path, "{\"rejected\":true}".toByteArray(), if (create) null else wrongTag)
                return false
            } catch (e: S3Exception) {
                if (e.sdkErrorMetadata.errorCode != "PreconditionFailed") throw e
            }
            val (actual, tag) = readDocument(path, 1024)
            if (!actual.contentEquals(original) || tag != first) return false
            if (!create) {
                val updated = "{\"verified\":true}".toByteArray()
                val updatedTag = putDocument(path, updated, first)
                val (after, fetchedTag) = readDocument(path, 1024)
                return after.contentEquals(updated) && updatedTag == fetchedTag && updatedTag != first
            }
            return true
        } catch (e: S3Exception) {
            if (e.sdkErrorMetadata.errorCode in setOf("NotImplemented", "UnsupportedOperation")) return false
            failure = e; throw e
        } catch (e: Throwable) { failure = e; throw e }
        finally {
            try { client.deleteObject { bucket = config.bucket; key = key(path) } }
            catch (cleanup: Throwable) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
        }
    }
    override fun ensureCollections() = request {
        // Independent probes make it possible to distinguish ignored creation and update guards.
        val checks = S3ConditionChecks(conditionProbe(true), conditionProbe(false))
        conditionChecks = checks
        // Only capability flags are logged: never endpoint, bucket, keys, documents or request bodies.
        android.util.Log.i("DailyOneS3", "${checks.description}；${if (revisionMode) "append-only revisions" else "conditional PUT"}")
        if (!revisionMode && (!checks.createProtected || !checks.updateProtected)) {
            throw IOException("${checks.description}；已停止同步以保护日记")
        }
    }
    private suspend fun listQiniuObjects(prefix: String): Map<String, String> {
        stage("列举", prefix)
        val root = key(prefix)
        val result = linkedMapOf<String, String>()
        val markers = mutableSetOf<String>()
        var marker: String? = null
        do {
            val page = client.listObjects { bucket = config.bucket; this.prefix = root; this.marker = marker }
            for (item in page.contents.orEmpty()) {
                val path = item.key ?: continue
                if (path.startsWith(root)) result[path.removePrefix(config.prefix)] = item.eTag.orEmpty()
            }
            marker = if (page.isTruncated == true) {
                page.nextMarker?.takeIf { it.isNotBlank() }
                    ?: page.contents?.lastOrNull()?.key?.takeIf { it.isNotBlank() }
                    ?: throw IOException("七牛 S3 分页响应缺少后续标记")
            } else null
            require(marker == null || markers.add(marker)) { "七牛 S3 返回了重复分页标记" }
        } while (marker != null)
        return result
    }
    private suspend fun listObjects(prefix: String): Map<String, String> {
        if (revisionMode) return listQiniuObjects(prefix)
        stage("列举", prefix)
        val root = key(prefix)
        val results = linkedMapOf<String, String>()
        val tokens = mutableSetOf<String>()
        var token: String? = null
        do {
            val page = client.listObjectsV2 { bucket = config.bucket; this.prefix = root; continuationToken = token }
            for (item in page.contents.orEmpty()) {
                val path = item.key ?: continue
                if (path.startsWith(root)) results[path.removePrefix(config.prefix)] = item.eTag.orEmpty()
            }
            token = if (page.isTruncated == true) requireNotNull(page.nextContinuationToken?.takeIf { it.isNotBlank() }) { "S3 分页响应缺少后续标记" } else null
            require(token == null || tokens.add(token)) { "S3 返回了重复分页标记" }
        } while (token != null)
        return results
    }
    private fun list(collection: String): Map<String, String> = request {
        if (revisionMode && collection != "photos") return@request revisions.listDocuments(collection)
        buildMap {
            for ((path, tag) in listObjects("$collection/")) {
                val name = path.removePrefix("$collection/")
                if (collection == "photos") { if (validPhoto(name)) put(name, "") }
                else if (name.endsWith(".json") && validId(name.removeSuffix(".json"))) put(name.removeSuffix(".json"), etag(tag))
            }
        }
    }
    override fun listEntries() = list("entries")
    override fun listNotebooks() = list("notebooks")
    override fun listPhotos() = list("photos").keys
    override fun listScanHashes(): Set<String> = request {
        listObjects("scan-history/").keys.mapNotNull { path ->
            path.removePrefix("scan-history/").takeIf { it.endsWith(".json") }
                ?.removeSuffix(".json")?.takeIf(::validScanHash)
        }.toSet()
    }
    override fun getScanHash(hash: String) = request {
        require(validScanHash(hash))
        validateScanHistory(readDocument("scan-history/$hash.json", 4096).first.toString(Charsets.UTF_8), hash)
    }
    override fun putScanHash(hash: String) = request {
        val bytes = scanHistoryJson(hash).toByteArray(Charsets.UTF_8)
        val path = "scan-history/$hash.json"
        try {
            stage("上传", path)
            client.putObject {
                bucket = config.bucket; key = key(path); body = ByteStream.fromBytes(bytes)
                ifNoneMatch = "*"; contentType = "application/json; charset=utf-8"
            }
        } catch (e: S3Exception) { if (e.sdkErrorMetadata.errorCode != "PreconditionFailed") throw e }
        validateScanHistory(readDocument(path, 4096).first.toString(Charsets.UTF_8), hash)
    }
    override fun listScanBatches(): Set<String> = request {
        listObjects("scan-history-batches/").keys.mapNotNull { path ->
            path.removePrefix("scan-history-batches/").takeIf { it.endsWith(".json") }
                ?.removeSuffix(".json")?.takeIf(::validScanHash)
        }.toSet()
    }
    override fun getScanBatch(id: String): String = request {
        require(validScanHash(id))
        readDocument("scan-history-batches/$id.json", SCAN_BATCH_LIMIT).first.toString(Charsets.UTF_8)
            .also { parseScanBatch(id, it) }
    }
    override fun putScanBatch(id: String, document: String) = request {
        parseScanBatch(id, document)
        val path = "scan-history-batches/$id.json"
        try {
            stage("上传", path)
            client.putObject {
                bucket = config.bucket; key = key(path); body = ByteStream.fromBytes(document.toByteArray(Charsets.UTF_8))
                ifNoneMatch = "*"; contentType = "application/json; charset=utf-8"
            }
        } catch (e: S3Exception) { if (e.sdkErrorMetadata.errorCode != "PreconditionFailed") throw e }
        parseScanBatch(id, readDocument(path, SCAN_BATCH_LIMIT).first.toString(Charsets.UTF_8))
        Unit
    }
    override fun getEntry(id: String): Entry = request {
        require(validId(id))
        if (revisionMode) {
            val (raw, tag) = revisions.getDocument("entries", id)
            return@request Entry.parse(raw, tag)
        }
        val (bytes, tag) = readDocument("entries/$id.json", 2 * 1024 * 1024)
        Entry.parse(bytes.toString(Charsets.UTF_8), tag).also { require(it.id == id) { "日记编号与文件不一致" } }
    }
    override fun getNotebook(id: String): Notebook = request {
        require(validId(id))
        if (revisionMode) {
            val (raw, tag) = revisions.getDocument("notebooks", id)
            return@request Notebook.parse(raw, tag)
        }
        val (bytes, tag) = readDocument("notebooks/$id.json", 65536)
        Notebook.parse(bytes.toString(Charsets.UTF_8), tag).also { require(it.id == id) { "日记本编号与文件不一致" } }
    }
    override fun putEntry(entry: Entry): String = request {
        if (revisionMode) revisions.putDocument("entries", entry.json(), entry.etag)
        else putDocument("entries/${entry.id}.json", entry.json().toByteArray(), entry.etag)
    }
    override fun putNotebook(book: Notebook): String = request {
        if (revisionMode) revisions.putDocument("notebooks", book.json(), book.etag)
        else putDocument("notebooks/${book.id}.json", book.json().toByteArray(), book.etag)
    }
    override fun putPhoto(name: String, file: File) = request {
        require(validPhoto(name))
        if (revisionMode) {
            try {
                stage("检查", "photos/$name")
                client.headObject { bucket = config.bucket; key = key("photos/$name") }
                return@request Unit
            } catch (e: S3Exception) {
                val missing = e.sdkErrorMetadata.errorCode in setOf("NotFound", "NoSuchKey", "404") ||
                    (e.sdkErrorMetadata.protocolResponse as? HttpResponse)?.status?.value == 404
                if (!missing) throw e
            }
        }
        try {
            stage("上传", "photos/$name")
            client.putObject { bucket = config.bucket; key = key("photos/$name"); body = ByteStream.fromFile(file); ifNoneMatch = "*"; contentType = "application/octet-stream" }
        } catch (e: S3Exception) { if (e.sdkErrorMetadata.errorCode != "PreconditionFailed") throw e }
        Unit
    }
    override fun downloadPhoto(name: String, destination: File) = request {
        require(validPhoto(name))
        stage("下载", "photos/$name")
        client.getObject(GetObjectRequest { bucket = config.bucket; key = key("photos/$name") }) { response ->
            requireNotNull(response.body) { "云端媒体内容为空" }.writeToFile(destination)
            check(response.contentLength == null || destination.length() == response.contentLength) { "原图下载不完整" }
        }
    }
    override fun cancelRequests() { job.cancel() }
    override fun close() { cancelRequests(); client.close() }
}

internal data class S3ConditionChecks(val createProtected: Boolean, val updateProtected: Boolean) {
    val description get() = "If-None-Match 新增保护${if (createProtected) "通过" else "失效"}；If-Match 更新保护${if (updateProtected) "通过" else "失效"}"
}

/** All uploads here have a known byte length. Avoid waiting on gateways that do not answer 100 Continue. */
internal fun S3Client.Config.Builder.applyS3GatewayCompatibility(config: S3Config) {
    if (config.endpoint.toHttpUrl().host.endsWith(".qiniucs.com")) continueHeaderThresholdBytes = Long.MAX_VALUE
}
