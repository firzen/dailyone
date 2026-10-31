package com.firzen.dailyone

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CoordinateConversionTest {
    @Test fun inverseConvergesWithoutChangingTheOriginalMapPoint() = runBlocking {
        val original = GeoPoint(23.1, 113.3, "原地点", coordinateSystem = CoordinateSystem.GCJ02, countryCode = "CN")
        val gps = invertMapCoordinate(original) { guess -> guess.copy(latitude = guess.latitude + .003, longitude = guess.longitude + .005, coordinateSystem = CoordinateSystem.GCJ02) }
        assertEquals(CoordinateSystem.WGS84, gps.coordinateSystem)
        assertEquals(23.097, gps.latitude, .000001); assertEquals(113.295, gps.longitude, .000001)
        assertEquals(23.1, original.latitude, 0.0); assertEquals("CN", gps.countryCode)
    }
    @Test fun unchangedCoordinatesOutsideOffsetAreaRemainUnchanged() = runBlocking {
        val point = GeoPoint(35.68,139.76,coordinateSystem = CoordinateSystem.GCJ02,countryCode="JP")
        val gps = invertMapCoordinate(point) { it.copy(coordinateSystem = CoordinateSystem.GCJ02) }
        assertEquals(point.copy(coordinateSystem = CoordinateSystem.WGS84), gps)
    }
    @Test fun nonConvergingConversionFailsInsteadOfReturningAWrongPoint() = runBlocking {
        val point = GeoPoint(23.1,113.3,coordinateSystem=CoordinateSystem.GCJ02)
        var calls = 0
        try {
            invertMapCoordinate(point) { calls++; point.copy(latitude=point.latitude+.01) }
            fail("Expected non-converging transform to fail")
        } catch (_: IllegalStateException) { assertEquals(6,calls) }
    }
}
