package com.firzen.dailyone

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

internal class AmapPlaces(private val key: String, private val client: OkHttpClient = defaultClient,
    private val base: HttpUrl = "https://restapi.amap.com/".toHttpUrl(), private val gate: PlaceSearchGate = sharedGate) {
    init { require(validAmapKey(key)) { "请先在设置中保存高德 Web服务 Key" } }
    suspend fun search(query: String, region: PlaceRegion, city: String, near: GeoPoint? = null): List<GeoPoint> {
        require(query.isNotBlank()) { "请输入地点名称或地址" }
        require(region != PlaceRegion.AUTO && region.provider == PlaceProvider.AMAP)
        val area = when (region) { PlaceRegion.HONG_KONG -> "香港"; PlaceRegion.MACAO -> "澳门"; else -> city.trim() }
        val center = near?.let { toMapCoordinate(it) }
        val localCity = when (region) { PlaceRegion.HONG_KONG -> "香港"; PlaceRegion.MACAO -> "澳门"; else -> near?.city.orEmpty() }
        fun normalizedCity(value: String) = value.removeSuffix("特别行政区").removeSuffix("市")
        val nearbyScope = near != null && PlaceRegion.fromPoint(near) == region &&
            (area.isBlank() || normalizedCity(area) == normalizedCity(localCity))
        val params = mutableMapOf("keywords" to query.trim(), "offset" to "25", "page" to "1", "extensions" to "all")
        fun parse(json: JSONObject): List<GeoPoint> {
            val pois = json.optJSONArray("pois") ?: throw IOException("高德搜索未返回有效地点列表")
            return (0 until pois.length()).mapNotNull { index -> parsePoi(pois.optJSONObject(index), region) }
                .filter { it.countryCode == region.countryCode }.distinctBy { it.coordinateKey() + it.name }
        }
        val local = if (nearbyScope && center != null) parse(request("v3/place/around", params +
            mapOf("location" to coordinates(center), "radius" to "50000", "sortrule" to "distance"))) else emptyList()
        val found = if (local.isNotEmpty()) local else {
            params["citylimit"] = (region != PlaceRegion.MAINLAND).toString()
            if (area.isNotBlank()) params["city"] = area
            parse(request("v3/place/text", params))
        }
        return if (center == null) found else nearestPlaces(found, center)
    }
    suspend fun reverse(point: GeoPoint): GeoPoint {
        val mapped = toMapCoordinate(point)
        val root = request("v3/geocode/regeo", mapOf("location" to coordinates(mapped), "extensions" to "all", "radius" to "100"))
        val regeo = root.optJSONObject("regeocode") ?: throw IOException("高德未返回有效地址")
        val component = regeo.optJSONObject("addressComponent") ?: JSONObject()
        val address = text(regeo, "formatted_address")
        val pois = regeo.optJSONArray("pois")
        val closePoi = (0 until (pois?.length() ?: 0)).mapNotNull { pois?.optJSONObject(it) }
            .filter { (text(it, "distance").toDoubleOrNull() ?: Double.POSITIVE_INFINITY) in 0.0..60.0 }
            .minByOrNull { text(it, "distance").toDoubleOrNull() ?: Double.POSITIVE_INFINITY }
        val name = closePoi?.let { text(it, "name") }?.takeIf { it.isNotBlank() } ?: address
        val country = country(text(component, "adcode"), text(component, "province"), text(component, "city"), "")
        require(name.isNotBlank()) { "高德暂未解析出该位置的名称" }
        // The original point and CRS are preserved, especially EXIF/GPS WGS84 coordinates.
        return point.copy(name = name, address = address, source = "AMap", countryCode = country,
            city = text(component, "city").ifBlank { text(component, "province") },
            poiId = closePoi?.let { text(it, "id") }.orEmpty(), nameOrigin = PlaceNameOrigin.AUTO)
    }
    private val convertedPoints = java.util.concurrent.ConcurrentHashMap<String, GeoPoint>()
    suspend fun toMapCoordinate(point: GeoPoint): GeoPoint {
        if (point.coordinateSystem == CoordinateSystem.GCJ02) return point
        convertedPoints[point.coordinateKey()]?.let { return it }
        val result = request("v3/assistant/coordinate/convert", mapOf("locations" to coordinates(point), "coordsys" to "gps"))
        val mapped = parseCoordinate(result.optString("locations").substringBefore(';')) ?: throw IOException("高德坐标转换未返回有效位置")
        val located = mapped.copy(countryCode = point.countryCode, city = point.city)
        convertedPoints[point.coordinateKey()] = located
        return located
    }
    private suspend fun nearbyResponse(point: GeoPoint, radius: Int = 1000, businessesOnly: Boolean = true, page: Int = 1): org.json.JSONArray {
        val mapped = toMapCoordinate(point)
        return request("v3/place/around", mapOf("location" to coordinates(mapped), "radius" to radius.toString(),
            "sortrule" to "distance", "types" to if (businessesOnly) "050000|060000|070000|080000|090000|100000" else ALL_PLACE_TYPES,
            "offset" to if (businessesOnly) "12" else "25", "page" to page.toString(), "extensions" to "all"))
            .optJSONArray("pois") ?: org.json.JSONArray()
    }
    suspend fun nearby(point: GeoPoint, page: Int = 1): List<GeoPoint> {
        val pois = nearbyResponse(point, businessesOnly = false, page = page)
        val region = PlaceRegion.fromPoint(point) ?: PlaceRegion.MAINLAND
        return (0 until pois.length()).mapNotNull { parsePoi(pois.optJSONObject(it), region) }
            .filter { PlaceRegion.fromPoint(it)?.provider == PlaceProvider.AMAP }
    }
    suspend fun refreshShop(point: GeoPoint): GeoPoint {
        val region = PlaceRegion.fromPoint(point) ?: PlaceRegion.MAINLAND
        val found = if (point.poiId.isNotBlank()) {
            val pois = request("v3/place/detail", mapOf("id" to point.poiId, "extensions" to "all")).optJSONArray("pois")
            pois?.optJSONObject(0)?.let { parsePoi(it, region) }
        } else {
            val pois = nearbyResponse(point, 150)
            (0 until pois.length()).mapNotNull { pois.optJSONObject(it) }
                .firstOrNull { (text(it, "distance").toDoubleOrNull() ?: Double.POSITIVE_INFINITY) in 0.0..75.0 }
                ?.let { parsePoi(it, region) }
        }
        if (found == null) return reverse(point).let { if (point.poiId.isNotBlank()) it.copy(name = point.name, poiId = point.poiId, nameOrigin = point.nameOrigin)
            else it.copy(name = point.name.ifBlank { it.address.ifBlank { it.name } }, poiId = "", nameOrigin = PlaceNameOrigin.AUTO) }
        return point.copy(name = found.name, address = found.address, source = "AMap", countryCode = found.countryCode,
            city = found.city, poiId = found.poiId, nameOrigin = PlaceNameOrigin.AUTO)
    }
    private suspend fun request(path: String, parameters: Map<String, String>): JSONObject = gate.request { requestInternal(path, parameters) }
    private suspend fun requestInternal(path: String, parameters: Map<String, String>): JSONObject {
        val builder = base.newBuilder().addPathSegments(path).addQueryParameter("key", key).addQueryParameter("output", "JSON")
        parameters.forEach { (name, value) -> builder.addQueryParameter(name, value) }
        val req = Request.Builder().url(builder.build()).header("Accept", "application/json").build()
        try {
            return response(req).use { response ->
                if (!response.isSuccessful) throw IOException("高德服务请求失败（HTTP ${response.code}），请稍后重试")
                withContext(Dispatchers.IO) {
                    val body = response.body ?: throw IOException("高德返回空内容")
                    require(body.contentLength() <= 2 * 1024 * 1024) { "高德返回数据过大" }
                    val output = java.io.ByteArrayOutputStream()
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        while (true) { val n = input.read(buffer); if (n < 0) break
                            require(output.size() + n <= 2 * 1024 * 1024) { "高德返回数据过大" }; output.write(buffer, 0, n) }
                    }
                    val json = try { JSONObject(output.toString("UTF-8")) } catch (_: Exception) { throw IOException("高德返回了无效数据") }
                    if (json.optString("status") != "1") throw IOException(errorMessage(json.optString("infocode")))
                    json
                }
            }
        } catch (_: java.net.UnknownHostException) { throw IOException("无法连接高德，请检查网络后重试") }
        catch (_: java.net.SocketTimeoutException) { throw IOException("高德请求超时，请稍后重试") }
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun response(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(IOException("连接高德失败，请检查网络后重试")))
            }
            override fun onResponse(call: Call, response: Response) { continuation.resume(response) { response.close() } }
        })
    }
    companion object {
        private val sharedGate = PlaceSearchGate(350)
        private val defaultClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
        private fun text(o: JSONObject, key: String): String = (o.opt(key) as? String)?.trim().orEmpty()
        private fun coordinates(p: GeoPoint): String = String.format(Locale.ROOT, "%.6f,%.6f", p.longitude, p.latitude)
        private fun country(adcode: String, province: String, city: String, fallback: String): String = when {
            adcode.startsWith("81") || province.contains("香港") || city.contains("香港") -> "HK"
            adcode.startsWith("82") || province.contains("澳门") || city.contains("澳门") -> "MO"
            adcode.startsWith("71") || province.contains("台湾") || province.contains("臺灣") -> "TW"
            adcode.isNotBlank() || province.isNotBlank() -> "CN"
            else -> fallback
        }
        internal fun parseCoordinate(value: String): GeoPoint? = runCatching {
            val parts = value.split(','); require(parts.size == 2)
            GeoPoint(parts[1].toDouble(), parts[0].toDouble(), coordinateSystem = CoordinateSystem.GCJ02)
        }.getOrNull()
        internal fun parsePoi(o: JSONObject?, region: PlaceRegion): GeoPoint? = o?.let { runCatching {
            val point = parseCoordinate(text(it, "location")) ?: return@runCatching null
            val name = text(it, "name"); if (name.isBlank()) return@runCatching null
            val city = text(it, "cityname")
            val address = listOf(text(it, "pname"), city, text(it, "adname"), text(it, "address"))
                .filter(String::isNotBlank).distinct().joinToString(" · ")
            point.copy(name = name, address = address, source = "AMap", city = city, poiId = text(it, "id"), nameOrigin = PlaceNameOrigin.POI,
                countryCode = country(text(it, "adcode"), text(it, "pname"), city, region.countryCode))
        }.getOrNull() }
        internal fun errorMessage(code: String): String = when (code) {
            "10001", "10009", "10013" -> "高德 Key 无效或已失效，请检查设置"
            "10005", "10006", "10007", "10008", "10012" -> "高德 Key 权限或平台不匹配，请使用已开通服务的 Web服务 Key"
            "10003", "10004", "10010", "10014", "10019", "10020", "10021", "10044" -> "高德调用额度或频率已受限，请稍后重试或检查控制台"
            else -> "高德暂时无法完成请求（${code.takeIf { it.all(Char::isDigit) && it.length == 5 } ?: "未知错误"}）"
        }
    }
}

// AMap POI categories, including addresses, public facilities, indoor facilities and entrances.
private val ALL_PLACE_TYPES = ((1..20).toList() + listOf(22, 97, 98, 99)).joinToString("|") { "%02d0000".format(java.util.Locale.ROOT, it) }
