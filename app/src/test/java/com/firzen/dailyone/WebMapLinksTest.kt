package com.firzen.dailyone

import org.junit.Assert.*
import org.junit.Test

class WebMapLinksTest {
    @Test fun gpsCoordinatesUseEachProvidersOrderAndExplicitCoordinateSystem() {
        val point = GeoPoint(39.908, 116.397)
        val amap = webMapUrl(PlaceProvider.AMAP, point)
        assertTrue(amap.contains("position=116.397,39.908"))
        assertTrue(amap.contains("coordinate=wgs84"))
        assertTrue(amap.contains("callnative=0"))
        assertTrue(webMapUrl(PlaceProvider.GOOGLE, point).contains("query=39.908,116.397"))
    }
    @Test fun gcjCoordinatesAreNeverPassedToGoogleAsGps() {
        val url = webMapUrl(PlaceProvider.GOOGLE, GeoPoint(39.9, 116.4, coordinateSystem = CoordinateSystem.GCJ02))
        assertTrue(url.startsWith("https://uri.amap.com/marker?"))
        assertTrue(url.contains("coordinate=gaode"))
    }
    @Test fun searchTextCannotInjectUrlParameters() {
        for (provider in PlaceProvider.entries) {
            val url = webMapUrl(provider, query = "咖啡 &key=secret#test")
            assertFalse(url.contains("&key="))
            assertFalse(url.contains("#test"))
            assertTrue(url.contains("%26key%3Dsecret%23test"))
        }
    }
    @Test fun noPositionStillOpensMapHome() {
        assertEquals("https://www.amap.com/", webMapUrl(PlaceProvider.AMAP))
        assertEquals("https://www.google.com/maps/", webMapUrl(PlaceProvider.GOOGLE))
    }
}
