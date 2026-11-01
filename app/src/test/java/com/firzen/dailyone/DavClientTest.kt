package com.firzen.dailyone

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

class DavClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var dav: DavClient
    private val id = "12345678-1234-1234-1234-123456789abc"
    @Before fun setUp() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server = MockWebServer()
        server.useHttps(serverCerts.sslSocketFactory(), false)
        server.start()
        client = OkHttpClient.Builder().sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager)
            .followRedirects(false).followSslRedirects(false).build()
        dav = DavClient(CloudConfig(server.url("/dav/").toString(), "user", "password"), client)
    }
    @After fun tearDown() { server.shutdown() }
    @Test fun immutableBatchVerifiesExistingContentAndListsWithoutEtags() {
        val batch = newScanBatch((1..512).map { "%064x".format(it) })
        server.enqueue(MockResponse().setResponseCode(412))
        server.enqueue(MockResponse().setBody(batch.document))
        dav.putScanBatch(batch.id, batch.document)
        val put = server.takeRequest()
        assertEquals("/dav/DailyOne/scan-history-batches/${batch.id}.json", put.path)
        assertEquals(batch.document, put.body.readUtf8()); assertEquals("*", put.getHeader("If-None-Match"))
        assertEquals("GET", server.takeRequest().method)
        server.enqueue(MockResponse().setBody(newScanBatch(listOf("f".repeat(64))).document))
        assertThrows(IllegalArgumentException::class.java) { dav.getScanBatch(batch.id) }
        server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(207).setBody("""
            <d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/DailyOne/scan-history-batches/${batch.id}.json</d:href></d:response></d:multistatus>
        """.trimIndent()))
        assertEquals(setOf(batch.id), dav.listScanBatches())
    }
    @Test fun scanHistoryVerifiesExistingMarkersAndRejectsMismatchedContent() {
        val hash = "a".repeat(64)
        server.enqueue(MockResponse().setResponseCode(412))
        server.enqueue(MockResponse().setBody(scanHistoryJson(hash)))
        dav.putScanHash(hash)
        val put = server.takeRequest()
        assertEquals("/dav/DailyOne/scan-history/$hash.json", put.path)
        assertEquals("*", put.getHeader("If-None-Match")); assertEquals(scanHistoryJson(hash), put.body.readUtf8())
        assertEquals("GET", server.takeRequest().method)
        server.enqueue(MockResponse().setBody(scanHistoryJson("b".repeat(64))))
        assertThrows(IllegalArgumentException::class.java) { dav.getScanHash(hash) }
        server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(207).setBody("""
            <d:multistatus xmlns:d="DAV:">
            <d:response><d:href>/dav/DailyOne/scan-history/$hash.json</d:href></d:response>
            <d:response><d:href>/dav/DailyOne/scan-history/invalid.json</d:href></d:response>
            </d:multistatus>
        """.trimIndent()))
        assertEquals(setOf(hash), dav.listScanHashes())
    }
    @Test fun collectionsAreCreatedUnderChosenDirectory() {
        repeat(6) { server.enqueue(MockResponse().setResponseCode(201)) }
        dav.ensureCollections()
        assertEquals("/dav/DailyOne/", server.takeRequest().path)
        assertEquals("/dav/DailyOne/entries/", server.takeRequest().path)
        assertEquals("/dav/DailyOne/photos/", server.takeRequest().path)
        assertEquals("/dav/DailyOne/notebooks/", server.takeRequest().path)
        assertEquals("/dav/DailyOne/scan-history/", server.takeRequest().path)
        assertEquals("/dav/DailyOne/scan-history-batches/", server.takeRequest().path)
    }
    @Test fun pastedUsernameWhitespaceIsRemovedWithoutChangingPassword() {
        val pasted = DavClient(CloudConfig(server.url("/dav/").toString(), " user\n", " password "), client)
        repeat(6) { server.enqueue(MockResponse().setResponseCode(201)) }
        pasted.ensureCollections()
        repeat(6) {
            assertEquals(okhttp3.Credentials.basic("user", " password "), server.takeRequest().getHeader("Authorization"))
        }
    }
    @Test fun notebookWritesUseConditionalEtagsAndVideoStreamsKeepMime() {
        val book = Notebook(id = id, name = "宝宝", etag = "\"old\"")
        server.enqueue(MockResponse().setResponseCode(204).setHeader("ETag", "\"new\""))
        assertEquals("\"new\"", dav.putNotebook(book))
        val write = server.takeRequest()
        assertEquals("/dav/DailyOne/notebooks/$id.json", write.path)
        assertEquals("\"old\"", write.getHeader("If-Match"))
        val file = java.io.File.createTempFile("video-fixture", ".mov")
        try {
            file.writeText("original video bytes")
            server.enqueue(MockResponse().setResponseCode(201))
            dav.putPhoto("$id.mov", file)
            val video = server.takeRequest()
            assertEquals("video/quicktime", video.getHeader("Content-Type"))
            assertEquals("original video bytes", video.body.readUtf8())
            assertEquals("*", video.getHeader("If-None-Match"))
        } finally { file.delete() }
    }
    @Test fun newEntryNeverOverwritesExistingRemoteRecord() {
        server.enqueue(MockResponse().setResponseCode(412))
        assertThrows(IOException::class.java) { dav.putEntry(Entry(id = id, body = "local")) }
        val request = server.takeRequest()
        assertEquals("*", request.getHeader("If-None-Match"))
        assertEquals("PUT", request.method)
    }
    @Test fun existingEntryUsesServerVersionInsteadOfTimestamps() {
        server.enqueue(MockResponse().setResponseCode(204).setHeader("ETag", "\"v2\""))
        assertEquals("\"v2\"", dav.putEntry(Entry(id = id, etag = "\"v1\"")))
        assertEquals("\"v1\"", server.takeRequest().getHeader("If-Match"))
    }
    @Test fun webdavNamespaceListingIsParsedWithoutTrustingRemotePaths() {
        server.enqueue(MockResponse().setResponseCode(207).setBody("""
            <d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/DailyOne/entries/$id.json</d:href>
            <d:propstat><d:prop><d:getetag>"v1"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
            </d:response></d:multistatus>
        """.trimIndent()))
        assertEquals(mapOf(id to "\"v1\""), dav.listEntries())
        val request = server.takeRequest()
        assertEquals("1", request.getHeader("Depth"))
        assertEquals("application/xml", request.getHeader("Content-Type"))
        assertTrue(request.body.readUtf8().contains("<d:allprop/>"))
    }
    @Test fun refusesXmlExternalEntities() {
        server.enqueue(MockResponse().setResponseCode(207).setBody("""
            <!DOCTYPE x [<!ENTITY external SYSTEM "file:///etc/passwd">]>
            <d:multistatus xmlns:d="DAV:">&external;</d:multistatus>
        """.trimIndent()))
        assertThrows(Exception::class.java) { dav.listEntries() }
    }
    @Test fun alternateByteEncodingCannotBypassDtdRejection() {
        val xml = "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><x>&e;</x>"
        server.enqueue(MockResponse().setResponseCode(207).setBody(okio.Buffer().write(xml.toByteArray(Charsets.UTF_16))))
        assertThrows(Exception::class.java) { dav.listEntries() }
    }
    @Test fun remoteEntryIdMustMatchRequestedFile() {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"v1\"").setBody(Entry().json()))
        assertThrows(IllegalArgumentException::class.java) { dav.getEntry(id) }
    }
    @Test fun unsupportedWeakEtagsFailSafely() {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "W/\"v1\"").setBody(Entry(id = id).json()))
        assertThrows(IllegalArgumentException::class.java) { dav.getEntry(id) }
    }
    @Test fun refusesPlainHttpCredentials() {
        assertThrows(IllegalArgumentException::class.java) { DavClient(CloudConfig("http://example.com", "user", "secret")) }
    }
    @Test fun oversizedRemoteDocumentsAreRejected() {
        server.enqueue(MockResponse().setHeader("ETag", "\"v1\"").setBody("x".repeat(2 * 1024 * 1024 + 1)))
        assertThrows(IllegalArgumentException::class.java) { dav.getEntry(id) }
    }
    @Test fun originalFilesLargerThanTwelveMegabytesStreamWithoutAlteration() {
        val source = java.io.File.createTempFile("dailyone-original", ".png")
        val destination = java.io.File.createTempFile("dailyone-download", ".png")
        try {
            val data = ByteArray(13 * 1024 * 1024 + 7) { (it % 251).toByte() }
            source.writeBytes(data)
            server.enqueue(MockResponse().setResponseCode(201))
            dav.putPhoto("$id.png", source)
            val request = server.takeRequest()
            assertEquals("image/png", request.getHeader("Content-Type"))
            assertEquals("*", request.getHeader("If-None-Match"))
            assertArrayEquals(data, request.body.readByteArray())
            server.enqueue(MockResponse().setBody(okio.Buffer().write(data)))
            dav.downloadPhoto("$id.png", destination)
            assertArrayEquals(data, destination.readBytes())
        } finally { source.delete(); destination.delete() }
    }

    @Test fun missingPutEtagIsAcceptedOnlyWhenDownloadedBytesMatch() {
        val entry = Entry(id = id, body = "uploaded", etag = "old")
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setHeader("ETag", "bare-nutstore-tag").setBody(entry.json()))
        assertEquals("bare-nutstore-tag", dav.putEntry(entry))
        assertEquals("old", server.takeRequest().getHeader("If-Match"))
        assertEquals("GET", server.takeRequest().method)
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setHeader("ETag", "someone-elses-version").setBody(entry.copy(body = "concurrent").json()))
        assertThrows(IOException::class.java) { dav.putEntry(entry) }
    }
    private fun nutstoreClient(): DavClient {
        val redirected = client.newBuilder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(chain.request().url.newBuilder()
                .host("localhost").port(server.port).build()).build())
        }.build()
        return DavClient(CloudConfig("https://dav.jianguoyun.com/dav/", "user", "password"), redirected)
    }
    @Test fun nutstoreCreationUsesNonOverwritingMoveAndVerifiesContent() {
        val entry = Entry(id = id, body = "new")
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(404)) // staging cleanup
        server.enqueue(MockResponse().setHeader("ETag", "version").setBody(entry.json()))
        assertEquals("version", nutstoreClient().putEntry(entry))
        val upload = server.takeRequest()
        assertTrue(upload.path!!.endsWith(".upload"))
        val move = server.takeRequest()
        assertEquals("MOVE", move.method)
        assertEquals("F", move.getHeader("Overwrite"))
        assertEquals("https://dav.jianguoyun.com/dav/DailyOne/entries/$id.json", move.getHeader("Destination"))
        assertEquals("DELETE", server.takeRequest().method)
        assertEquals("GET", server.takeRequest().method)
    }
    @Test fun nutstoreCreationCollisionPreservesRemoteAndCleansStaging() {
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(409))
        server.enqueue(MockResponse().setResponseCode(204))
        assertThrows(IOException::class.java) { nutstoreClient().putEntry(Entry(id = id)) }
        val upload = server.takeRequest()
        assertEquals("MOVE", server.takeRequest().method)
        val cleanup = server.takeRequest()
        assertEquals("DELETE", cleanup.method)
        assertEquals(upload.path, cleanup.path)
    }

    @Test fun photoListingRecognizesCompletedMediaWithoutRequiringEtags() {
        server.enqueue(MockResponse().setResponseCode(207).setBody("""
            <d:multistatus xmlns:d="DAV:">
            <d:response><d:href>/dav/DailyOne/photos/$id.mp4</d:href><d:propstat><d:prop/></d:propstat></d:response>
            <d:response><d:href>/dav/DailyOne/photos/$id.mp4.upload</d:href></d:response>
            </d:multistatus>
        """.trimIndent()))
        assertEquals(setOf("$id.mp4"), dav.listPhotos())
    }

}
