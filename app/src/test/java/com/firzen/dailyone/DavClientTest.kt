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
    private lateinit var dav: DavClient
    private val id = "12345678-1234-1234-1234-123456789abc"
    @Before fun setUp() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server = MockWebServer()
        server.useHttps(serverCerts.sslSocketFactory(), false)
        server.start()
        val client = OkHttpClient.Builder().sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager)
            .followRedirects(false).followSslRedirects(false).build()
        dav = DavClient(CloudConfig(server.url("/dav/").toString(), "user", "password"), client)
    }
    @After fun tearDown() { server.shutdown() }
    @Test fun collectionsAreCreatedUnderChosenDirectory() {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(201)) }
        dav.ensureCollections()
        assertEquals("/dav/DailyOne/", server.takeRequest().path)
        assertEquals("/dav/DailyOne/entries/", server.takeRequest().path)
        assertEquals("/dav/DailyOne/photos/", server.takeRequest().path)
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
        assertEquals("1", server.takeRequest().getHeader("Depth"))
    }
    @Test fun refusesXmlExternalEntities() {
        server.enqueue(MockResponse().setResponseCode(207).setBody("""
            <!DOCTYPE x [<!ENTITY external SYSTEM "file:///etc/passwd">]>
            <d:multistatus xmlns:d="DAV:">&external;</d:multistatus>
        """.trimIndent()))
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
}
