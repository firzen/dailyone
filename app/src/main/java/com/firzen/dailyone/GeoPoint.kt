package com.firzen.dailyone

import org.json.JSONObject
import java.util.Locale

data class GeoPoint(val latitude: Double, val longitude: Double, val name: String = "", val address: String = "", val source: String = "") {
    init { require(latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0) }
    fun json(): JSONObject = JSONObject().put("latitude", latitude).put("longitude", longitude).put("name", name).put("address", address).put("source", source)
    fun label(): String = name.ifBlank { address }.ifBlank { "已记录位置" }
    fun coordinateKey(): String = "$latitude,$longitude"
    fun sameCoordinates(other: GeoPoint): Boolean = latitude == other.latitude && longitude == other.longitude
    fun coordinates(): String = String.format(Locale.ROOT, "%.6f, %.6f", latitude, longitude)
    companion object {
        fun parse(value: JSONObject?): GeoPoint? = value?.let { runCatching { GeoPoint(it.getDouble("latitude"), it.getDouble("longitude"), it.optString("name"), it.optString("address"), it.optString("source")) }.getOrNull() }
    }
}
