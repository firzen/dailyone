package com.firzen.dailyone

import org.json.JSONObject
import java.util.Locale

data class GeoPoint(val latitude: Double, val longitude: Double) {
    init { require(latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0) }
    fun json(): JSONObject = JSONObject().put("latitude", latitude).put("longitude", longitude)
    fun label(): String = String.format(Locale.ROOT, "%.6f, %.6f", latitude, longitude)
    companion object {
        fun parse(value: JSONObject?): GeoPoint? = value?.let { runCatching { GeoPoint(it.getDouble("latitude"), it.getDouble("longitude")) }.getOrNull() }
    }
}
