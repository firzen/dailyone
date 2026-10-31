package com.firzen.dailyone

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface PlaceLookup {
    suspend fun reverse(point: GeoPoint): GeoPoint?
    suspend fun search(query: String): List<GeoPoint>
}

/** The device's system geocoder; no map SDK or embedded API credentials. */
class PlaceService(context: Context) : PlaceLookup {
    private val geocoder = Geocoder(context.applicationContext, Locale.SIMPLIFIED_CHINESE)
    private val cache = context.getSharedPreferences("place_names", Context.MODE_PRIVATE)
    override suspend fun reverse(point: GeoPoint): GeoPoint? {
        cache.getString(point.coordinateKey(), null)?.let { raw ->
            GeoPoint.parse(runCatching { org.json.JSONObject(raw) }.getOrNull())?.let { return it }
        }
        val result = lookup(point, null).firstOrNull()?.toPlace()?.let {
            // Geocoding describes the coordinate; never shift a photo's or journal's actual coordinate.
            point.copy(name = it.name, address = it.address)
        }
        if (result != null) cache.edit().putString(point.coordinateKey(), result.json().toString()).apply()
        return result
    }
    override suspend fun search(query: String): List<GeoPoint> {
        require(query.isNotBlank()) { "请输入地点名称或地址" }
        return lookup(null, query.trim()).mapNotNull { it.toPlace() }.distinctBy { it.coordinateKey() + it.name }
    }
    @Suppress("DEPRECATION")
    private suspend fun lookup(point: GeoPoint?, query: String?): List<Address> = withTimeoutOrNull(15_000) {
        check(Geocoder.isPresent()) { "系统地点服务暂不可用，可先为已有位置填写名称" }
        if (Build.VERSION.SDK_INT >= 33) suspendCancellableCoroutine { continuation ->
            val listener = object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) {
                    if (continuation.isActive) continuation.resume(addresses)
                }
                override fun onError(errorMessage: String?) {
                    if (continuation.isActive) continuation.resumeWithException(java.io.IOException("地点服务暂不可用，请稍后重试"))
                }
            }
            if (point != null) geocoder.getFromLocation(point.latitude, point.longitude, 1, listener)
            else geocoder.getFromLocationName(requireNotNull(query), 5, listener)
        } else withContext(Dispatchers.IO) {
            if (point != null) geocoder.getFromLocation(point.latitude, point.longitude, 1).orEmpty()
            else geocoder.getFromLocationName(requireNotNull(query), 5).orEmpty()
        }
    } ?: throw java.io.IOException("地点查询超时，请稍后重试")
}

internal fun Address.toPlace(): GeoPoint? {
    if (!hasLatitude() || !hasLongitude()) return null
    val address = (0..maxAddressLineIndex).mapNotNull { getAddressLine(it) }.distinct().joinToString(" ").trim()
    val title = listOf(featureName?.takeUnless { it.all(Char::isDigit) }, premises, thoroughfare, subLocality, locality, adminArea)
        .firstOrNull { !it.isNullOrBlank() } ?: address
    return runCatching { GeoPoint(latitude, longitude, title, address) }.getOrNull()
}
