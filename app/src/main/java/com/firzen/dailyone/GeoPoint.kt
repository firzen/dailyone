package com.firzen.dailyone

import org.json.JSONObject
import java.util.Locale

enum class PlaceNameOrigin { LEGACY, AUTO, POI, CUSTOM }

enum class CoordinateSystem { WGS84, GCJ02 }

data class GeoPoint(val latitude: Double, val longitude: Double, val name: String = "", val address: String = "", val source: String = "",
    val coordinateSystem: CoordinateSystem = CoordinateSystem.WGS84, val countryCode: String = "", val city: String = "", val poiId: String = "", val nameOrigin: PlaceNameOrigin = PlaceNameOrigin.LEGACY) {
    init { require(latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0) }
    fun json(): JSONObject = JSONObject().put("latitude", latitude).put("longitude", longitude).put("name", name).put("address", address).put("source", source).put("coordinateSystem", coordinateSystem.name).put("countryCode", countryCode).put("city", city).put("poiId", poiId).put("nameOrigin", nameOrigin.name)
    fun label(): String = name.ifBlank { address }.ifBlank { "已记录位置" }
    fun coordinateKey(): String = (if (coordinateSystem == CoordinateSystem.WGS84) "" else "${coordinateSystem.name}:") + "$latitude,$longitude"
    fun sameCoordinates(other: GeoPoint): Boolean = coordinateSystem == other.coordinateSystem && latitude == other.latitude && longitude == other.longitude
    fun coordinates(): String = String.format(Locale.ROOT, "%.6f, %.6f", latitude, longitude)
    companion object {
        fun parse(value: JSONObject?): GeoPoint? = value?.let { runCatching { GeoPoint(it.getDouble("latitude"), it.getDouble("longitude"), it.optString("name"), it.optString("address"), it.optString("source"), CoordinateSystem.valueOf(it.optString("coordinateSystem", "WGS84")), it.optString("countryCode"), it.optString("city"), it.optString("poiId"), PlaceNameOrigin.valueOf(it.optString("nameOrigin", "LEGACY"))) }.getOrNull() }
    }
}
