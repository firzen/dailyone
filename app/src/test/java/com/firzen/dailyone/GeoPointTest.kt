package com.firzen.dailyone

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class GeoPointTest {
    @Test fun coordinatesValidateWithoutDiscardingZero() {
        assertEquals(GeoPoint(0.0, 0.0), GeoPoint.parse(GeoPoint(0.0, 0.0).json()))
        assertNull(GeoPoint.parse(JSONObject().put("latitude", 91).put("longitude", 0)))
        assertThrows(IllegalArgumentException::class.java) { GeoPoint(Double.NaN, 0.0) }
    }
    @Test fun positionFollowsPhotoOrderAndSurvivesRoundTrip() {
        val first = "${UUID.randomUUID()}.heic"; val second = "${UUID.randomUUID()}.png"
        val a = GeoPoint(31.0, 121.0); val b = GeoPoint(-22.0, 0.0)
        val entry = Entry(photos = listOf(first, second), photoLocations = mapOf(first to a, second to b))
        assertEquals(a, Entry.parse(entry.json()).location)
        assertEquals(b, entry.copy(photos = listOf(second, first)).location)
        assertEquals(b, entry.copy(photos = listOf(second)).location)
        assertEquals(a, entry.copy(recordedLocation = a, photos = listOf(second)).location)
        val legacy = JSONObject(entry.json()).apply { remove("photoLocations"); remove("location") }
        assertNull(Entry.parse(legacy.toString()).location)
    }
}
