package com.firzen.dailyone

import org.junit.Assert.*
import org.junit.Test

class PlaceRegionTest {
    @Test fun onlyMainlandHongKongAndMacaoUseAmap() {
        for (country in listOf("CN", "HK", "MO")) assertEquals(PlaceProvider.AMAP, PlaceRegion.fromPoint(GeoPoint(0.0, 0.0, countryCode = country))?.provider)
        for (country in listOf("TW", "US", "JP", "VN", "SG")) assertEquals(PlaceProvider.GOOGLE, PlaceRegion.fromPoint(GeoPoint(0.0, 0.0, countryCode = country))?.provider)
    }
    @Test fun explicitDestinationWinsOverCurrentCountry() {
        assertEquals(PlaceRegion.HONG_KONG, PlaceRegion.resolve(PlaceRegion.HONG_KONG, GeoPoint(35.0, 139.0, countryCode = "JP")))
        assertEquals(PlaceRegion.MACAO, PlaceRegion.resolve(PlaceRegion.AUTO, GeoPoint(22.2, 113.5, countryCode = "MO")))
    }
    @Test fun unknownCountryIsNotGuessedFromARectangle() {
        assertNull(PlaceRegion.fromPoint(GeoPoint(22.0, 105.0)))
        assertThrows(IllegalArgumentException::class.java) { PlaceRegion.resolve(PlaceRegion.AUTO, GeoPoint(22.0, 105.0)) }
    }
    @Test fun pickerDefaultsToAmapOnlyForTheThreeDetectedRegions() {
        for (country in listOf("CN", "HK", "MO")) assertEquals(PlaceProvider.AMAP, PlaceProvider.defaultFor(GeoPoint(0.0, 0.0, countryCode = country)))
        for (country in listOf("JP", "TW", "US", "")) assertEquals(PlaceProvider.GOOGLE, PlaceProvider.defaultFor(GeoPoint(0.0, 0.0, countryCode = country)))
        assertEquals(PlaceProvider.GOOGLE, PlaceProvider.defaultFor(null))
    }

}
