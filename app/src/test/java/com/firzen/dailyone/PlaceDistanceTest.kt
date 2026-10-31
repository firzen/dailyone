package com.firzen.dailyone

import org.junit.Assert.*
import org.junit.Test

class PlaceDistanceTest {
    @Test fun sortsNearestFirstInsteadOfProviderRelevanceOrder() {
        val center = GeoPoint(23.1, 113.3, coordinateSystem = CoordinateSystem.GCJ02)
        val near = center.copy(latitude = 23.1001, name = "最近")
        val far = center.copy(latitude = 23.15, name = "较远")
        val middle = center.copy(latitude = 23.11, name = "中间")
        assertEquals(listOf(near, middle, far), nearestPlaces(listOf(far, near, middle), center))
        assertTrue(distanceMeters(center, near) in 10.0..12.0)
    }
    @Test fun distancesNeverMixGpsAndMapCoordinates() {
        assertThrows(IllegalArgumentException::class.java) { distanceMeters(GeoPoint(23.1, 113.3), GeoPoint(23.1, 113.3, coordinateSystem = CoordinateSystem.GCJ02)) }
        assertEquals("300米", distanceLabel(300.0)); assertEquals("1.2公里", distanceLabel(1200.0))
    }
}
