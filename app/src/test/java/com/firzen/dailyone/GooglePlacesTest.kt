package com.firzen.dailyone

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.json.JSONObject
import java.io.IOException

class GooglePlacesTest {
    private lateinit var server: MockWebServer
    private lateinit var google: GooglePlaces
    private val dummy = "AIza" + "a".repeat(35)
    private val tokyo = GeoPoint(35.68, 139.76, coordinateSystem = CoordinateSystem.WGS84, countryCode = "JP")
    @Before fun setup() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        google = GooglePlaces(dummy, OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build(), server.url("/"), server.url("/"))
    }
    @After fun cleanup() { server.shutdown() }
    private fun poi(id: String, name: String, lat: Double, lng: Double, country: String) = """{"id":"$id","displayName":{"text":"$name"},"formattedAddress":"测试地址","location":{"latitude":$lat,"longitude":$lng},"addressComponents":[{"shortText":"$country","types":["country"]},{"longText":"东京","types":["locality"]}]}"""
    @Test fun searchUsesNewApiAndRetainsWgsCoordinatesSortedByDistance() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"places":[${poi("far", "远地点", 35.8,139.8,"JP")},${poi("near", "近地点",35.6801,139.7601,"JP")},${poi("cn", "国内地点",23.1,113.3,"CN")}]}"""))
        val results = google.search("书店", tokyo)
        assertEquals(listOf("近地点", "远地点"), results.map { it.name })
        assertTrue(results.all { it.source == "Google" && it.coordinateSystem == CoordinateSystem.WGS84 && it.countryCode == "JP" })
        val request = server.takeRequest()
        assertEquals("/v1/places:searchText", request.path)
        assertEquals(dummy, request.getHeader("X-Goog-Api-Key"))
        assertFalse(request.path!!.contains(dummy))
        assertFalse(request.getHeader("X-Goog-FieldMask")!!.contains("*"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals(139.76, body.getJSONObject("locationBias").getJSONObject("circle").getJSONObject("center").getDouble("longitude"), 0.0)
    }
    @Test fun nearbyIncludesAllTypesAndRanksByDistanceWithoutInventingPagination() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"places":[${poi("park", "公园",35.681,139.761,"JP")}]}"""))
        assertEquals("公园", google.nearby(tokyo).single().name)
        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertEquals("/v1/places:searchNearby", request.path)
        assertEquals("DISTANCE", body.getString("rankPreference"))
        assertEquals(20, body.getInt("maxResultCount"))
        assertFalse(body.has("includedTypes")); assertFalse(body.has("pageToken"))
    }
    @Test fun reverseKeepsOriginalCoordinatesAndParsesCountry() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"OK","results":[{"formatted_address":"日本东京都测试路","address_components":[{"short_name":"JP","long_name":"日本","types":["country"]},{"long_name":"东京","types":["locality"]}]}]}"""))
        val result = requireNotNull(google.reverse(tokyo))
        assertTrue(result.sameCoordinates(tokyo)); assertEquals("JP", result.countryCode); assertEquals("东京", result.city)
        assertEquals("Google", result.source)
        assertEquals("/maps/api/geocode/json", server.takeRequest().requestUrl?.encodedPath)
    }
    @Test fun googleNeverReceivesGcjCoordinates() = runBlocking {
        try { google.nearby(tokyo.copy(coordinateSystem = CoordinateSystem.GCJ02)); fail("Expected coordinate rejection") }
        catch (_: IllegalArgumentException) { assertEquals(0, server.requestCount) }
        server.enqueue(MockResponse().setBody("{}"))
        google.search("东京", GeoPoint(23.1,113.3,coordinateSystem=CoordinateSystem.GCJ02,countryCode="CN"))
        assertFalse(JSONObject(server.takeRequest().body.readUtf8()).has("locationBias"))
    }
    @Test fun deniedRequestsDoNotLeakServerMessagesOrKeys() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"message":"$dummy"}}"""))
        try { google.nearby(tokyo); fail("Expected denied request") }
        catch (e: IOException) { assertTrue(e.message!!.contains("未获授权")); assertFalse(e.message!!.contains(dummy)) }
    }
    @Test fun providerCreditsRoundTripWithSelectedLocation() {
        val p = JSONObject(poi("public-fixture", "公园",35.68,139.76,"JP"))
            .put("attributions", org.json.JSONArray("""[{"provider":"来源名称","providerUri":"https://example.com/credit"}]"""))
        val point = requireNotNull(GooglePlaces.parsePlace(p))
        assertEquals(listOf(PlaceAttribution("来源名称", "https://example.com/credit")), point.attributions)
        assertEquals(point, GeoPoint.parse(point.json()))
    }
}
