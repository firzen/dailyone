package com.firzen.dailyone

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Google requests and returned coordinates are WGS84. Responses are not persistently cached. */
class GooglePlaces(private val key: String, private val client: OkHttpClient = defaultClient,
    private val placesBase: HttpUrl = "https://places.googleapis.com/".toHttpUrl(),
    private val geocodingBase: HttpUrl = "https://maps.googleapis.com/".toHttpUrl()) {
    init { require(validGoogleKey(key)) { "地点服务暂不可用，请稍后重试" } }
    suspend fun search(query: String, near: GeoPoint?, includeAmapRegions: Boolean = false): List<GeoPoint> {
        val body = JSONObject().put("textQuery", query.trim()).put("languageCode", "zh-CN").put("pageSize", 20)
        val center = near?.takeIf { it.coordinateSystem == CoordinateSystem.WGS84 && (includeAmapRegions || PlaceRegion.fromPoint(it)?.provider != PlaceProvider.AMAP) }
        if (center != null) body.put("locationBias", JSONObject().put("circle", circle(center, 50000.0)))
        val found = parsePlaces(post("v1/places:searchText", body), includeAmapRegions)
        return if (center == null) found else nearestPlaces(found, center)
    }
    suspend fun nearby(point: GeoPoint, includeAmapRegions: Boolean = false): List<GeoPoint> {
        require(point.coordinateSystem == CoordinateSystem.WGS84) { "Google 地图需要 GPS 坐标，不能使用高德坐标" }
        val body = JSONObject().put("languageCode", "zh-CN").put("maxResultCount", 20).put("rankPreference", "DISTANCE")
            .put("locationRestriction", JSONObject().put("circle", circle(point, 1000.0)))
        // Nearby Search (New) has no pagination token and supports at most 20 places.
        return nearestPlaces(parsePlaces(post("v1/places:searchNearby", body), includeAmapRegions), point)
    }
    suspend fun reverse(point: GeoPoint): GeoPoint? {
        require(point.coordinateSystem == CoordinateSystem.WGS84) { "Google 地址解析需要 GPS 坐标" }
        val url = geocodingBase.newBuilder().addPathSegments("maps/api/geocode/json")
            .addQueryParameter("latlng", "${point.latitude},${point.longitude}").addQueryParameter("language", "zh-CN")
            .addQueryParameter("key", key).build()
        val root = request(Request.Builder().url(url).build())
        when (root.optString("status")) {
            "ZERO_RESULTS" -> return null
            "OK" -> Unit
            "REQUEST_DENIED" -> throw IOException("Google 地址服务暂未获授权，请稍后重试")
            "OVER_QUERY_LIMIT", "OVER_DAILY_LIMIT" -> throw IOException("Google 地址服务额度或调用频率受限")
            else -> throw IOException("Google 暂时无法解析地址，请稍后重试")
        }
        val result = root.optJSONArray("results")?.optJSONObject(0) ?: return null
        val components = result.optJSONArray("address_components") ?: JSONArray()
        val address = result.optString("formatted_address")
        if (address.isBlank()) return null
        return point.copy(name = address, address = address, source = "Google", countryCode = component(components, "country", "short_name"),
            city = component(components, "locality", "long_name").ifBlank { component(components, "administrative_area_level_1", "long_name") },
            poiId = "", nameOrigin = PlaceNameOrigin.AUTO, attributions = emptyList())
    }
    private fun circle(point: GeoPoint, radius: Double) = JSONObject().put("center", JSONObject()
        .put("latitude", point.latitude).put("longitude", point.longitude)).put("radius", radius)
    private suspend fun post(path: String, body: JSONObject): JSONObject = request(Request.Builder()
        .url(placesBase.newBuilder().addPathSegments(path).build()).header("X-Goog-Api-Key", key)
        .header("X-Goog-FieldMask", "places.id,places.displayName,places.formattedAddress,places.location,places.addressComponents,places.attributions")
        .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build())
    private suspend fun request(request: Request): JSONObject {
        val response = response(request)
        return response.use {
            withContext(Dispatchers.IO) {
                if (!it.isSuccessful) throw IOException(when (it.code) {
                    400 -> "Google 地点服务暂不可用，请稍后重试"
                    401, 403 -> "Google 地点服务暂未获授权，请稍后重试"
                    429 -> "Google 地点服务额度或调用频率受限，请稍后重试"
                    else -> "Google 地点服务暂时不可用，请稍后重试"
                })
                val input = it.body ?: throw IOException("Google 返回空内容")
                if (input.contentLength() > 2 * 1024 * 1024) throw IOException("Google 返回数据过大")
                val output = java.io.ByteArrayOutputStream()
                input.byteStream().use { stream ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = stream.read(buffer); if (n < 0) break
                        if (output.size() + n > 2 * 1024 * 1024) throw IOException("Google 返回数据过大")
                        output.write(buffer, 0, n)
                    }
                }
                try { JSONObject(output.toString("UTF-8")) } catch (_: Exception) { throw IOException("Google 返回无效数据") }
            }
        }
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun response(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(IOException("无法连接 Google，请检查网络后重试")))
            }
            override fun onResponse(call: Call, response: Response) { continuation.resume(response) { response.close() } }
        })
    }
    private fun parsePlaces(root: JSONObject, includeAmapRegions: Boolean): List<GeoPoint> {
        val places = root.optJSONArray("places") ?: return emptyList()
        return (0 until places.length()).mapNotNull { parsePlace(places.optJSONObject(it)) }
            .filter { includeAmapRegions || PlaceRegion.fromPoint(it)?.provider == PlaceProvider.GOOGLE }
            .distinctBy { it.poiId.ifBlank { it.coordinateKey() + it.name } }
    }
    companion object {
        private val defaultClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
        private fun component(components: JSONArray, type: String, key: String): String = (0 until components.length())
            .mapNotNull { components.optJSONObject(it) }.firstOrNull { c ->
                val types = c.optJSONArray("types") ?: JSONArray(); (0 until types.length()).any { types.optString(it) == type }
            }?.optString(key).orEmpty()
        internal fun parsePlace(place: JSONObject?): GeoPoint? = place?.let { runCatching {
            val location = it.getJSONObject("location")
            val components = it.optJSONArray("addressComponents") ?: JSONArray()
            val country = component(components, "country", "shortText")
            if (country.isBlank()) return@runCatching null
            val name = it.optJSONObject("displayName")?.optString("text").orEmpty()
            if (name.isBlank()) return@runCatching null
            val attrs = it.optJSONArray("attributions") ?: JSONArray()
            GeoPoint(location.getDouble("latitude"), location.getDouble("longitude"), name, it.optString("formattedAddress"), "Google",
                countryCode = country, city = component(components, "locality", "longText"), poiId = it.optString("id"), nameOrigin = PlaceNameOrigin.POI,
                attributions = (0 until attrs.length()).mapNotNull { i -> attrs.optJSONObject(i)?.let { a ->
                    a.optString("provider").takeIf(String::isNotBlank)?.let { provider -> PlaceAttribution(provider, a.optString("providerUri")) }
                } })
        }.getOrNull() }
    }
}
internal fun validGoogleKey(value: String): Boolean = Regex("AIza[0-9A-Za-z_-]{35}").matches(value)
