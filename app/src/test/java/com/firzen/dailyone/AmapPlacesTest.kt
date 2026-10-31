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
import java.io.IOException

class AmapPlacesTest {
    private lateinit var server: MockWebServer
    private lateinit var amap: AmapPlaces
    private val dummy = "1".repeat(32)
    @Before fun setup() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        amap = AmapPlaces(dummy, OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build(), server.url("/"), PlaceSearchGate(0))
    }
    @After fun cleanup() { server.shutdown() }
    private fun reply(pois: String) = server.enqueue(MockResponse().setBody("""{"status":"1","infocode":"10000","pois":$pois}"""))
    @Test fun mainlandSearchRequestsFullRegionalMetadataAndRetainsGcjCoordinates() = runBlocking {
        reply("""[{"name":"广州塔","location":"113.33,23.11","pname":"广东省","cityname":"广州市","adname":"海珠区","adcode":"440105","address":"测试路1号"}]""")
        val point = amap.search("广州塔", PlaceRegion.MAINLAND, "广州").single()
        assertEquals(CoordinateSystem.GCJ02, point.coordinateSystem); assertEquals("CN", point.countryCode)
        assertEquals("AMap", point.source); assertEquals(113.33, point.longitude, 0.0)
        val request = requireNotNull(server.takeRequest().requestUrl)
        assertEquals("广州", request.queryParameter("city")); assertEquals("all", request.queryParameter("extensions"))
        assertEquals("false", request.queryParameter("citylimit")); assertEquals("广州塔", request.queryParameter("keywords"))
    }
    @Test fun hongKongAndMacaoHaveExplicitSearchScopes() = runBlocking {
        for ((region, name, code) in listOf(Triple(PlaceRegion.HONG_KONG, "香港", "810000"), Triple(PlaceRegion.MACAO, "澳门", "820000"))) {
            reply("""[{"name":"咖啡店","location":"114.1,22.3","pname":"${name}特别行政区","cityname":"$name","adcode":"$code"}]""")
            assertEquals(region.countryCode, amap.search("咖啡店", region, "广州").single().countryCode)
            val request = requireNotNull(server.takeRequest().requestUrl)
            assertEquals(name, request.queryParameter("city")); assertEquals("true", request.queryParameter("citylimit"))
        }
    }
    @Test fun mainlandScopeDoesNotAcceptTaiwanOrHongKongResults() = runBlocking {
        reply("""[{"name":"台北地点","location":"121.5,25.0","pname":"台湾省","adcode":"710000"},{"name":"香港地点","location":"114.1,22.3","pname":"香港特别行政区","adcode":"810000"}]""")
        assertTrue(amap.search("地点", PlaceRegion.MAINLAND, "").isEmpty())
    }
    @Test fun photoGpsConvertsOnlyForTheRequestAndNeverMovesStoredPoint() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"1","locations":"113.305,23.097"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"1","regeocode":{"formatted_address":"广州市测试路","addressComponent":{"province":"广东省","city":"广州市","adcode":"440105"},"pois":[{"name":"附近咖啡店","distance":"20"}]}}"""))
        val original = GeoPoint(23.1, 113.3)
        val named = amap.reverse(original)
        assertTrue(original.sameCoordinates(named)); assertEquals(CoordinateSystem.WGS84, named.coordinateSystem)
        assertEquals("附近咖啡店", named.name)
        assertEquals("gps", server.takeRequest().requestUrl?.queryParameter("coordsys"))
        assertEquals("113.305000,23.097000", server.takeRequest().requestUrl?.queryParameter("location"))
    }
    @Test fun gcjResultsAreNeverFedThroughGpsConversionAgain() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"1","regeocode":{"formatted_address":"香港测试路","addressComponent":{"province":"香港特别行政区","adcode":"810000"}}}"""))
        val point = GeoPoint(22.3, 114.1, coordinateSystem = CoordinateSystem.GCJ02, countryCode = "HK")
        assertTrue(amap.reverse(point).sameCoordinates(point))
        assertEquals("/v3/geocode/regeo", server.takeRequest().requestUrl?.encodedPath)
        assertEquals(1, server.requestCount)
    }
    @Test fun providerErrorsExplainKeyPlatformWithoutLeakingCredentials() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"0","infocode":"10008","info":"$dummy"}"""))
        try { amap.search("地点", PlaceRegion.MAINLAND, "广州"); fail("Expected key error") }
        catch (e: IOException) { assertTrue(e.message!!.contains("Web服务")); assertFalse(e.message!!.contains(dummy)) }
    }
    @Test fun shopRefreshUsesNearbyBusinessesWithoutMovingGps() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"1","locations":"113.305,23.097"}"""))
        reply("""[{"id":"shop-fixture","name":"附近咖啡店","distance":"20","location":"113.3051,23.0971","pname":"广东省","cityname":"广州市","adcode":"440105"}]""")
        val original = GeoPoint(23.1, 113.3, "旧街道", countryCode = "CN")
        val updated = amap.refreshShop(original)
        assertTrue(updated.sameCoordinates(original)); assertEquals("附近咖啡店", updated.name)
        assertEquals("shop-fixture", updated.poiId); assertEquals(PlaceNameOrigin.AUTO, updated.nameOrigin)
        server.takeRequest()
        val request = requireNotNull(server.takeRequest().requestUrl)
        assertEquals("/v3/place/around", request.encodedPath)
        assertEquals("distance", request.queryParameter("sortrule"))
        assertTrue(requireNotNull(request.queryParameter("types")).contains("050000"))
    }
    @Test fun missingHistoricalPoiDoesNotRenameItToAnotherBusiness() = runBlocking {
        reply("[]")
        server.enqueue(MockResponse().setBody("""{"status":"1","regeocode":{"formatted_address":"原位置的新地址","addressComponent":{"province":"香港特别行政区","adcode":"810000"}}}"""))
        val original = GeoPoint(22.3, 114.1, "历史店名", coordinateSystem = CoordinateSystem.GCJ02, countryCode = "HK", poiId = "old-shop", nameOrigin = PlaceNameOrigin.POI)
        val updated = amap.refreshShop(original)
        assertEquals("历史店名", updated.name); assertTrue(updated.sameCoordinates(original))
        assertEquals("old-shop", updated.poiId)
    }

    @Test fun sameCityQueriesUseDistanceSortedNearbySearch() = runBlocking {
        reply("""[{"name":"远店","location":"113.35,23.15","pname":"广东省","cityname":"广州市","adcode":"440105"},{"name":"近店","location":"113.3001,23.1001","pname":"广东省","cityname":"广州市","adcode":"440105"}]""")
        val center = GeoPoint(23.1, 113.3, coordinateSystem = CoordinateSystem.GCJ02, countryCode = "CN", city = "广州市")
        assertEquals(listOf("近店", "远店"), amap.search("咖啡店", PlaceRegion.MAINLAND, "广州", center).map { it.name })
        val req = requireNotNull(server.takeRequest().requestUrl)
        assertEquals("/v3/place/around", req.encodedPath)
        assertEquals("distance", req.queryParameter("sortrule"))
        assertEquals("113.300000,23.100000", req.queryParameter("location"))
    }

}
