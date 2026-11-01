package com.firzen.dailyone

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.S3Client
import aws.smithy.kotlin.runtime.net.url.Url
import aws.smithy.kotlin.runtime.retries.StandardRetryStrategy
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Local HTTP fixture only. No device or real S3 credentials are involved. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class S3StorageTest {
    private val objects = ConcurrentHashMap<String, Pair<ByteArray, String>>()
    private val counter = AtomicInteger()
    private lateinit var server: MockWebServer
    private lateinit var storage: S3Storage
    @Volatile private var ignoreConditions = false
    @Volatile private var ignoreCreate = false
    @Volatile private var ignoreUpdate = false
    @Volatile private var failNextPut = false
    @Volatile private var brokenPagination = false
    @Volatile private var omitPutEtag = false
    @Volatile private var hangNextGet = false
    @Volatile private var noContinueResponse = false
    @Volatile private var listCalls = 0
    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun peek() = if (noContinueResponse) MockResponse() else MockResponse().setSocketPolicy(SocketPolicy.EXPECT_CONTINUE)
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                if (request.method == "GET" && (url.queryParameter("list-type") == "2" || url.queryParameter("prefix") != null)) {
                    listCalls++
                    val prefix = url.queryParameter("prefix").orEmpty()
                    val v2 = url.queryParameter("list-type") == "2"
                    val start = url.queryParameter(if (v2) "continuation-token" else "marker")?.toIntOrNull() ?: 0
                    val sorted = objects.keys.filter { it.startsWith(prefix) }.sorted()
                    val batch = sorted.drop(start).take(1000)
                    val truncated = start + batch.size < sorted.size
                    val nextField = if (v2) "NextContinuationToken" else "NextMarker"
                    val next = if (truncated && !brokenPagination) "<$nextField>${start + batch.size}</$nextField>" else ""
                    return MockResponse().setBody("""<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><IsTruncated>$truncated</IsTruncated>$next${batch.joinToString("") { "<Contents><Key>$it</Key><ETag>${objects.getValue(it).second}</ETag></Contents>" }}</ListBucketResult>""")
                }
                val path = url.pathSegments.drop(1).joinToString("/")
                return when (request.method) {
                    "PUT" -> {
                        if (failNextPut) { failNextPut = false; return error(503, "ServiceUnavailable") }
                        val before = objects[path]
                        if (!ignoreConditions && ((!ignoreCreate && request.getHeader("If-None-Match") == "*" && before != null) ||
                            (!ignoreUpdate && request.getHeader("If-Match") != null && request.getHeader("If-Match") != before?.second))) return error(412, "PreconditionFailed")
                        val tag = "\"opaque-${counter.incrementAndGet()}\""
                        objects[path] = request.body.readByteArray() to tag
                        if (omitPutEtag) MockResponse() else MockResponse().setHeader("ETag", tag)
                    }
                    "GET" -> if (hangNextGet) { hangNextGet = false; MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) } else objects[path]?.let { MockResponse().setHeader("ETag", it.second).setBody(okio.Buffer().write(it.first)) } ?: error(404, "NoSuchKey")
                    "HEAD" -> objects[path]?.let { MockResponse().setHeader("ETag", it.second).setHeader("Content-Length", it.first.size) } ?: MockResponse().setResponseCode(404)
                    "DELETE" -> { objects.remove(path); MockResponse().setResponseCode(204) }
                    else -> error(405, "MethodNotAllowed")
                }
            }
        }
        server.start()
        storage = newStorage()
    }
    private fun error(status: Int, code: String) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/xml")
        .setBody("<Error><Code>$code</Code><Message>fixture</Message></Error>")
    private fun newStorage(qiniu: Boolean = false, readTimeoutMs: Long? = null): S3Storage {
        val config = S3Config(if (qiniu) "https://s3.cn-south-1.qiniucs.com" else server.url("/").toString().replace("http:", "https:"), "us-east-1", "journals", "fixture", "fixture", pathStyle = true)
        val client = S3Client {
            region = "us-east-1"; endpointUrl = Url.parse(server.url("/").toString()); forcePathStyle = true; enableAwsChunked = false
            applyS3GatewayCompatibility(config)
            if (readTimeoutMs != null) httpClient { socketReadTimeout = readTimeoutMs.milliseconds }
            credentialsProvider = StaticCredentialsProvider { accessKeyId = "fixture"; secretAccessKey = "fixture" }
            retryStrategy = StandardRetryStrategy { maxAttempts = 1 }
        }
        return S3Storage(config, client)
    }
    @After fun tearDown() { if (::storage.isInitialized) storage.close(); if (::server.isInitialized) server.shutdown() }
    @Test fun verifiesConditionalWritesAndOnlyDeletesItsOwnProbe() {
        val entry = Entry(body = "保留内容")
        objects["DailyOne/entries/${entry.id}.json"] = entry.json().toByteArray() to "\"existing\""
        storage.ensureCollections()
        assertEquals(setOf("DailyOne/entries/${entry.id}.json"), objects.keys)
        assertTrue((0 until server.requestCount).map { server.takeRequest() }.all { it.getHeader("Authorization")?.startsWith("AWS4-HMAC-SHA256 ") == true })
    }
    @Test fun rejectsServersThatIgnoreConditionalHeaders() {
        ignoreConditions = true
        assertThrows(IOException::class.java) { storage.ensureCollections() }
        assertTrue(objects.isEmpty())
    }
    @Test fun identifiesCreationAndUpdateFailuresIndependently() {
        ignoreCreate = true
        val creation = assertThrows(IOException::class.java) { storage.ensureCollections() }
        assertTrue(creation.message!!.contains("If-None-Match 新增保护失效"))
        assertEquals(S3ConditionChecks(false, true), storage.conditionChecks)
        ignoreCreate = false; ignoreUpdate = true
        assertThrows(IOException::class.java) { storage.ensureCollections() }
        assertEquals(S3ConditionChecks(true, false), storage.conditionChecks)
        ignoreCreate = true
        assertThrows(IOException::class.java) { storage.ensureCollections() }
        assertEquals(S3ConditionChecks(false, false), storage.conditionChecks)
        assertTrue(objects.isEmpty())
    }
    @Test fun qiniuUsesImmutableVersionsWhenBothConditionalHeadersAreIgnored() {
        ignoreConditions = true
        storage.close(); storage = newStorage(qiniu = true)
        storage.ensureCollections()
        assertEquals(S3ConditionChecks(false, false), storage.conditionChecks)
        val entry = Entry(body = "第一版")
        val first = storage.putEntry(entry)
        storage.putEntry(entry.copy(body = "第二版", etag = first))
        assertEquals("第二版", storage.getEntry(entry.id).body)
        assertEquals(setOf(entry.id), storage.listEntries().keys)
        assertEquals(2, objects.keys.count { it.startsWith("DailyOne/.versions/entries/") })
        assertFalse(objects.containsKey("DailyOne/entries/${entry.id}.json"))
        assertTrue(objects.values.any { it.first.toString(Charsets.UTF_8).contains("第一版") })
    }
    @Test fun qiniuTwoSdkClientsPreserveConcurrentChangesAsSeparateRecords() {
        ignoreConditions = true
        storage.close(); storage = newStorage(qiniu = true)
        val other = newStorage(qiniu = true)
        try {
            val initial = Entry(body = "共同起点")
            storage.putEntry(initial)
            val a = storage.getEntry(initial.id)
            val b = other.getEntry(initial.id)
            storage.putEntry(a.copy(body = "设备 A 的修改"))
            other.putEntry(b.copy(body = "设备 B 的修改"))
            storage.close(); storage = newStorage(qiniu = true)
            val ids = storage.listEntries().keys
            assertEquals(2, ids.size)
            assertEquals(setOf("设备 A 的修改", "设备 B 的修改"), ids.map { storage.getEntry(it).body }.toSet())
            assertEquals(ids, storage.listEntries().keys)
        } finally { other.close() }
    }
    @Test fun qiniuListsAllMediaUsingMarkerPagination() {
        storage.close(); storage = newStorage(qiniu = true)
        repeat(1001) { i ->
            val id = "%08x-0000-0000-0000-%012x".format(i, i)
            objects["DailyOne/photos/$id.jpg"] = byteArrayOf() to "\"$i\""
        }
        assertEquals(1001, storage.listPhotos().size)
        assertEquals(2, listCalls)
        val first = server.takeRequest().requestUrl!!; val second = server.takeRequest().requestUrl!!
        assertNull(first.queryParameter("list-type"))
        assertEquals("1000", second.queryParameter("marker"))
    }
    @Test fun qiniuMediaUsesTheExistingImmutableNameWithoutReplacingAnExistingFile() {
        ignoreConditions = true
        storage.close(); storage = newStorage(qiniu = true)
        val source = File.createTempFile("qiniu-media", ".jpg")
        val target = File.createTempFile("qiniu-download", ".jpg")
        val name = "${Entry().id}.jpg"
        try {
            source.writeText("原始图片")
            storage.putPhoto(name, source)
            source.writeText("其他内容")
            storage.putPhoto(name, source)
            storage.downloadPhoto(name, target)
            assertEquals("原始图片", target.readText())
        } finally { source.delete(); target.delete() }
    }
    @Test(timeout = 20000) fun qiniuLargeUploadDoesNotWaitFor100Continue() {
        noContinueResponse = true
        storage.close(); storage = newStorage(qiniu = true, readTimeoutMs = 2000)
        val source = File.createTempFile("qiniu-no-continue", ".mp4")
        val name = "${Entry().id}.mp4"
        val stages = mutableListOf<String>()
        storage.observeRequests { stages += it }
        try {
            val data = ByteArray(13 * 1024 * 1024 + 7) { (it % 251).toByte() }
            source.writeBytes(data)
            storage.putPhoto(name, source)
            val head = server.takeRequest(); val upload = server.takeRequest()
            assertEquals("HEAD", head.method)
            assertEquals("PUT", upload.method)
            assertNull(upload.getHeader("Expect"))
            assertArrayEquals(data, objects.getValue("DailyOne/photos/$name").first)
            assertTrue(stages.contains("上传媒体"))
            assertFalse(stages.any { name in it || "fixture" in it || "localhost" in it })
        } finally { source.delete() }
    }
    @Test fun timeoutReportsTheExactOperationWithoutExposingConfiguration() {
        storage.close(); storage = newStorage(readTimeoutMs = 300)
        hangNextGet = true
        val error = assertThrows(IOException::class.java) { storage.getEntry(Entry().id) }
        assertTrue(error.message!!.startsWith("读取日记："))
        assertTrue(error.message!!.contains("网络超时"))
        assertFalse(error.message!!.contains("fixture"))
        assertFalse(error.message!!.contains("localhost"))
    }
    @Test fun entriesAndNotebooksRoundTripWithStaleWritesRejected() {
        val entry = Entry(body = "正文")
        val first = storage.putEntry(entry)
        assertEquals(entry.copy(dirty = false, etag = first), storage.getEntry(entry.id))
        val changed = entry.copy(body = "更新", etag = first)
        val second = storage.putEntry(changed)
        assertNotEquals(first, second)
        assertThrows(IOException::class.java) { storage.putEntry(changed) }
        assertEquals("更新", storage.getEntry(entry.id).body)
        val book = Notebook(name = "日记本")
        val tag = storage.putNotebook(book)
        assertEquals(book.copy(dirty = false, etag = tag), storage.getNotebook(book.id))
    }
    @Test fun listsMoreThanOneThousandObjectsAndRejectsIncompletePagination() {
        repeat(1001) { i ->
            val id = "%08x-0000-0000-0000-%012x".format(i, i)
            objects["DailyOne/entries/$id.json"] = byteArrayOf() to "\"$i\""
        }
        objects["DailyOne/entries/nested/ignore.json"] = byteArrayOf() to "\"ignored\""
        assertEquals(1001, storage.listEntries().size)
        assertEquals(2, listCalls)
        brokenPagination = true
        assertThrows(IllegalArgumentException::class.java) { storage.listEntries() }
    }
    @Test(timeout = 30000) fun streamsOriginalMediaAndDoesNotOverwriteExistingObjects() {
        val source = File.createTempFile("s3-original", ".mp4")
        val destination = File.createTempFile("s3-downloaded", ".mp4")
        try {
            val data = ByteArray(13 * 1024 * 1024 + 7) { (it % 251).toByte() }
            source.writeBytes(data)
            val name = "${Entry().id}.mp4"
            storage.putPhoto(name, source)
            source.writeText("不同内容")
            storage.putPhoto(name, source)
            assertEquals(setOf(name), storage.listPhotos())
            storage.downloadPhoto(name, destination)
            assertArrayEquals(data, destination.readBytes())
        } finally { source.delete(); destination.delete() }
    }
    @Test fun interruptedUploadCanBeRetriedWithANewSession() {
        val entry = Entry(body = "尚未上传")
        failNextPut = true
        assertThrows(IOException::class.java) { storage.putEntry(entry) }
        storage.close(); storage = newStorage()
        storage.putEntry(entry)
        assertEquals(entry.body, storage.getEntry(entry.id).body)
    }
    @Test fun missingPutEtagIsVerifiedByReadingBackTheExactDocument() {
        omitPutEtag = true
        val entry = Entry(body = "验证读回")
        val tag = storage.putEntry(entry)
        assertEquals(entry.copy(dirty = false, etag = tag), storage.getEntry(entry.id))
    }
    @Test(timeout = 15000) fun cancellationInterruptsAnInFlightRequest() {
        val entry = Entry()
        hangNextGet = true
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<Entry> { storage.getEntry(entry.id) }
            assertNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS))
            storage.cancelRequests()
            val failure = assertThrows(java.util.concurrent.ExecutionException::class.java) {
                future.get(5, java.util.concurrent.TimeUnit.SECONDS)
            }
            assertTrue(failure.cause is kotlinx.coroutines.CancellationException)
        } finally { executor.shutdownNow() }
    }
    @Test fun cancellationStopsFurtherRequestsAndUnsafeDocumentsAreRejected() {
        val entry = Entry()
        objects["DailyOne/entries/${entry.id}.json"] = "x".repeat(2 * 1024 * 1024 + 1).toByteArray() to "\"oversized\""
        assertThrows(IllegalArgumentException::class.java) { storage.getEntry(entry.id) }
        storage.cancelRequests()
        assertThrows(kotlinx.coroutines.CancellationException::class.java) { storage.listEntries() }
    }
}
