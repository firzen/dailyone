package com.firzen.dailyone

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class OpenMapSearchTest {
    private lateinit var server: MockWebServer
    private lateinit var search: OpenMapSearch
    private val result = """{"features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[113.324,23.106]},"properties":{"name":"广州塔","country":"中国","state":"广东省","city":"广州市","district":"海珠区"}}]}"""
    @Before fun setup() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCert = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientCert = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server = MockWebServer().apply { useHttps(serverCert.sslSocketFactory(), false); start() }
        search = OpenMapSearch(server.url("/places/").toString(), OkHttpClient.Builder()
            .sslSocketFactory(clientCert.sslSocketFactory(), clientCert.trustManager).build(), PlaceSearchGate(0))
    }
    @After fun cleanup() { server.shutdown() }
    @Test fun searchesUnicodeNamesWithLocationBiasAndCorrectCoordinateOrder() = runBlocking {
        server.enqueue(MockResponse().setBody(result))
        val places = search.search(" 广州塔 ", GeoPoint(23.12345, 113.98765))
        assertEquals(GeoPoint(23.106, 113.324, "广州塔", "中国 · 广东省 · 广州市 · 海珠区", "OpenStreetMap"), places.single())
        val request = server.takeRequest()
        assertEquals("/places/api/", request.requestUrl?.encodedPath)
        assertEquals("广州塔", request.requestUrl?.queryParameter("q"))
        assertEquals("23.123", request.requestUrl?.queryParameter("lat"))
        assertEquals("113.988", request.requestUrl?.queryParameter("lon"))
        assertTrue(requireNotNull(request.getHeader("User-Agent")).startsWith("DailyOne/"))
    }
    @Test fun emptyResultsAreDistinctFromServiceFailures() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"features":[]}"""))
        assertTrue(search.search("未收录地点").isEmpty())
        server.enqueue(MockResponse().setResponseCode(429))
        try { search.search("限流"); fail("Expected rate-limit error") } catch (e: IOException) { assertTrue(e.message!!.contains("过于频繁")) }
        server.enqueue(MockResponse().setBody("""{"error":"wrong endpoint"}"""))
        try { search.search("错误服务"); fail("Expected incompatible-server error") } catch (e: IOException) { assertTrue(e.message!!.contains("格式不匹配")) }
    }
    @Test fun rejectsInvalidCoordinatesAndNonPointResults() {
        val raw = """{"features":[{"geometry":{"type":"Point","coordinates":[113,91]},"properties":{"name":"invalid"}},{"geometry":{"type":"Polygon","coordinates":[]},"properties":{"name":"wrong type"}}]}"""
        assertTrue(OpenMapSearch.parseResults(raw).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { OpenMapSearch.normalizedEndpoint("http://example.com/") }
        assertThrows(IllegalArgumentException::class.java) { OpenMapSearch.normalizedEndpoint("https://user:secret@example.com/") }
    }
    @Test fun cancellingARequestReleasesTheNextSearch() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val pending = launch { search.search("取消请求") }
        assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) })
        pending.cancelAndJoin()
        server.enqueue(MockResponse().setBody(result))
        assertEquals("广州塔", search.search("下一次搜索").single().name)
    }
    @Test fun requestGateSpacesRequestsAcrossClients() = runBlocking {
        val gate = PlaceSearchGate(70)
        val first = gate.request { System.nanoTime() }
        val second = gate.request { System.nanoTime() }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(second - first) >= 60)
    }
}
