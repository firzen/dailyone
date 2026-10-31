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

/** Provider selection uses the requested region or an identified country, never a rough bounding box. */
class PlaceService(context: Context) : PlaceLookup {
    private val settings = MapSettings(context.applicationContext)
    private val searchCache = context.getSharedPreferences("amap_search_cache", Context.MODE_PRIVATE)
    private val geocoder = Geocoder(context.applicationContext, Locale.SIMPLIFIED_CHINESE)
    private val cache = context.getSharedPreferences("place_names", Context.MODE_PRIVATE)
    private var activeClient: Pair<String, AmapPlaces>? = null
    private suspend fun amapClient(): AmapPlaces {
        val key = withContext(Dispatchers.IO) { settings.loadAmapKey() }
        require(validAmapKey(key)) { "请先在设置中保存高德 Web服务 Key" }
        return activeClient?.takeIf { it.first == key }?.second ?: AmapPlaces(key).also { activeClient = key to it }
    }
    private var googleClient: Pair<String, GooglePlaces>? = null
    private suspend fun googleClient(): GooglePlaces {
        val key = withContext(Dispatchers.IO) { settings.loadGoogleKey() }
        require(validGoogleKey(key)) { "请先在设置中保存 Google 地点服务 Key" }
        return googleClient?.takeIf { it.first == key }?.second ?: GooglePlaces(key).also { googleClient = key to it }
    }
    private suspend fun identify(point: GeoPoint): GeoPoint = if (point.countryCode.isBlank() && point.coordinateSystem == CoordinateSystem.WGS84)
        systemReverse(point)?.let { named -> point.copy(countryCode = named.countryCode, city = named.city) } ?: point else point
    suspend fun mapCoordinate(point: GeoPoint): GeoPoint {
        val identified = identify(point)
        if (PlaceRegion.fromPoint(identified)?.provider == PlaceProvider.GOOGLE) {
            require(identified.coordinateSystem == CoordinateSystem.WGS84) { "Google 地图需要原始 GPS 坐标" }
            return identified
        }
        return amapClient().toMapCoordinate(identified)
    }
    suspend fun nearby(point: GeoPoint, page: Int = 1): List<GeoPoint> =
        if (PlaceRegion.fromPoint(point)?.provider == PlaceProvider.GOOGLE) {
            if (page == 1) googleClient().nearby(point) else emptyList()
        } else amapClient().nearby(point, page)
    suspend fun refreshShop(point: GeoPoint): GeoPoint {
        val identified = if (point.countryCode.isBlank() && point.coordinateSystem == CoordinateSystem.WGS84) systemReverse(point) ?: point else point
        if (PlaceRegion.fromPoint(identified)?.provider != PlaceProvider.AMAP) throw UnsupportedOperationException("地区未知或属于其他地区，Google 店铺更新尚未接入")
        return amapClient().refreshShop(point)
    }
    override suspend fun reverse(point: GeoPoint): GeoPoint? {
        if (point.source == "Google" || PlaceRegion.fromPoint(point)?.provider == PlaceProvider.GOOGLE) {
            val named = googleClient().reverse(point) ?: return null
            return if (PlaceRegion.fromPoint(named)?.provider == PlaceProvider.AMAP) amapClient().reverse(point.copy(countryCode = named.countryCode, city = named.city)) else named
        }
        val key = withContext(Dispatchers.IO) { settings.loadAmapKey() }
        val known = cached(point.coordinateKey())
        val system = if (point.coordinateSystem == CoordinateSystem.WGS84) {
            if (point.countryCode.isNotBlank()) point
            else known?.takeIf { it.countryCode.isNotBlank() } ?: systemReverse(point)
        } else point
        val region = PlaceRegion.fromPoint(system)
        if (region?.provider == PlaceProvider.GOOGLE) return googleClient().reverse(point.copy(countryCode = system?.countryCode.orEmpty(), city = system?.city.orEmpty()))
        if (point.coordinateSystem == CoordinateSystem.GCJ02 || region?.provider == PlaceProvider.AMAP && key.isNotBlank()) {
            require(validAmapKey(key)) { "请先在设置中保存高德 Web服务 Key" }
            val cacheKey = "amap:${sha256(key.toByteArray())}:${point.coordinateKey()}"
            cached(cacheKey)?.let { return it }
            return amapClient().reverse(point).also { cache.edit().putString(cacheKey, it.json().toString()).apply() }
        }
        val result = system?.takeIf { it.name.isNotBlank() || it.address.isNotBlank() }
            ?: if (point.coordinateSystem == CoordinateSystem.WGS84) known ?: systemReverse(point) else null
        return result?.also { cache.edit().putString(point.coordinateKey(), it.json().toString()).apply() }
    }
    private fun cached(key: String): GeoPoint? = cache.getString(key, null)?.let { GeoPoint.parse(runCatching { org.json.JSONObject(it) }.getOrNull()) }
    override suspend fun search(query: String): List<GeoPoint> = search(query, null, PlaceRegion.MAINLAND, "")
    suspend fun search(query: String, near: GeoPoint?, requested: PlaceRegion, city: String): List<GeoPoint> {
        require(query.isNotBlank()) { "请输入地点名称或地址" }
        val identified = if (requested == PlaceRegion.AUTO && near != null && near.countryCode.isBlank() && near.coordinateSystem == CoordinateSystem.WGS84)
            systemReverse(near) ?: near else near
        val region = PlaceRegion.resolve(requested, identified)
        if (region.provider == PlaceProvider.GOOGLE) return googleClient().search(query, identified)
        val key = withContext(Dispatchers.IO) { settings.loadAmapKey() }
        require(validAmapKey(key)) { "请先在设置中保存高德 Web服务 Key" }
        val inheritedCity = identified?.takeIf { PlaceRegion.fromPoint(it) == PlaceRegion.MAINLAND }?.city.orEmpty()
        val area = if (region == PlaceRegion.MAINLAND) city.trim().ifBlank { inheritedCity } else ""
        val cacheKey = sha256("amap-v2|$key|${region.name}|$area|${identified?.coordinateKey()}|${query.trim()}".toByteArray(Charsets.UTF_8))
        val now = System.currentTimeMillis()
        runCatching {
            val cached = org.json.JSONObject(searchCache.getString(cacheKey, "") ?: "")
            if (now < cached.getLong("expires")) {
                val array = cached.getJSONArray("results")
                return (0 until array.length()).mapNotNull { GeoPoint.parse(array.optJSONObject(it)) }
            }
        }
        val result = amapClient().search(query, region, area, identified)
        val ttl = if (result.isEmpty()) 5 * 60_000L else 7 * 24 * 60 * 60_000L
        val payload = org.json.JSONObject().put("expires", now + ttl)
            .put("results", org.json.JSONArray().apply { result.forEach { put(it.json()) } })
        val editor = searchCache.edit()
        if (searchCache.all.size >= 128) searchCache.all.keys.take(32).forEach { editor.remove(it) }
        editor.putString(cacheKey, payload.toString()).apply()
        return result
    }
    @Suppress("DEPRECATION")
    private suspend fun systemReverse(point: GeoPoint): GeoPoint? = withTimeoutOrNull(10_000) {
        if (!Geocoder.isPresent()) return@withTimeoutOrNull null
        val addresses = if (Build.VERSION.SDK_INT >= 33) suspendCancellableCoroutine { continuation ->
            geocoder.getFromLocation(point.latitude, point.longitude, 1, object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) { if (continuation.isActive) continuation.resume(addresses) }
                override fun onError(errorMessage: String?) { if (continuation.isActive) continuation.resume(emptyList()) }
            })
        } else withContext(Dispatchers.IO) { geocoder.getFromLocation(point.latitude, point.longitude, 1).orEmpty() }
        addresses.firstOrNull()?.toPlace()?.let { point.copy(name = it.name, address = it.address, countryCode = it.countryCode, city = it.city) }
    }
}

internal fun Address.toPlace(): GeoPoint? {
    if (!hasLatitude() || !hasLongitude()) return null
    val address = (0..maxAddressLineIndex).mapNotNull { getAddressLine(it) }.distinct().joinToString(" ").trim()
    val title = listOf(featureName?.takeUnless { it.all(Char::isDigit) }, premises, thoroughfare, subLocality, locality, adminArea)
        .firstOrNull { !it.isNullOrBlank() } ?: address
    return runCatching { GeoPoint(latitude, longitude, title, address, countryCode = countryCode.orEmpty(), city = locality.orEmpty(), nameOrigin = PlaceNameOrigin.AUTO) }.getOrNull()
}
