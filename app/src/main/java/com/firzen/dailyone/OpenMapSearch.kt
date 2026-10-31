package com.firzen.dailyone

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/** A shared gate limits all manual searches, including calls from different service instances. */
internal class PlaceSearchGate(private val intervalMillis: Long = 1100) {
    private val mutex = Mutex()
    private var nextStart = 0L
    suspend fun <T> request(action: suspend () -> T): T = mutex.withLock {
        delay(((nextStart - System.nanoTime()) / 1_000_000).coerceAtLeast(0))
        nextStart = System.nanoTime() + intervalMillis * 1_000_000
        action()
    }
}

internal class OpenMapSearch(endpoint: String = DEFAULT_ENDPOINT,
    private val client: OkHttpClient = defaultClient,
    private val gate: PlaceSearchGate = sharedGate) {
    private val base = normalizedEndpoint(endpoint).toHttpUrl()
    suspend fun search(query: String, near: GeoPoint? = null): List<GeoPoint> {
        require(query.isNotBlank()) { "请输入地点名称或地址" }
        val builder = base.newBuilder().addPathSegment("api").addPathSegment("")
            .addQueryParameter("q", query.trim()).addQueryParameter("limit", "6")
        if (near != null) {
            builder.addQueryParameter("lat", String.format(Locale.ROOT, "%.3f", near.latitude))
                .addQueryParameter("lon", String.format(Locale.ROOT, "%.3f", near.longitude))
        }
        val request = Request.Builder().url(builder.build())
            .header("User-Agent", "DailyOne/0.5.1 (+https://github.com/firzen/dailyone)")
            .header("Accept", "application/json").header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6").build()
        return gate.request {
            try {
                response(request).use { response ->
                    if (!response.isSuccessful) throw IOException(when (response.code) {
                        429 -> "地点搜索过于频繁，请稍后再试"
                        403 -> "搜索服务器拒绝访问，可在设置中更换地点搜索服务器"
                        404 -> "地点搜索服务器地址不正确，请在设置中检查"
                        in 300..399 -> "搜索服务器要求跳转，请在设置中填写最终 HTTPS 地址"
                        else -> "开放地图服务暂时无法响应（${response.code}），请稍后重试"
                    })
                    withContext(Dispatchers.IO) {
                        val body = response.body ?: throw IOException("搜索服务器返回了空内容")
                        require(body.contentLength() <= 1024 * 1024) { "地点搜索结果过大" }
                        val output = java.io.ByteArrayOutputStream()
                        body.byteStream().use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer); if (count < 0) break
                                require(output.size() + count <= 1024 * 1024) { "地点搜索结果过大" }
                                output.write(buffer, 0, count)
                            }
                        }
                        parseResults(output.toString("UTF-8"))
                    }
                }
            } catch (e: java.net.UnknownHostException) { throw IOException("无法连接地点搜索服务器，请检查网络或更换服务器地址", e) }
            catch (e: java.net.SocketTimeoutException) { throw IOException("地点搜索连接超时，请稍后重试或更换服务器地址", e) }
        }
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun response(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWith(Result.failure(e)) }
            override fun onResponse(call: Call, response: Response) { continuation.resume(response) { response.close() } }
        })
    }
    companion object {
        const val DEFAULT_ENDPOINT = "https://photon.komoot.io/"
        private val sharedGate = PlaceSearchGate()
        private val defaultClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()
        fun normalizedEndpoint(value: String): String {
            val url = value.trim().toHttpUrl()
            require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
                "请填写不含账号或查询参数的 HTTPS 服务地址"
            }
            return url.toString().trimEnd('/') + "/"
        }
        internal fun parseResults(raw: String): List<GeoPoint> {
            val root = try { JSONObject(raw) } catch (e: Exception) { throw IOException("地点搜索服务器返回了无效数据", e) }
            val features = root.optJSONArray("features") ?: throw IOException("地点搜索服务器格式不匹配，请使用 Photon 服务地址")
            return (0 until features.length()).mapNotNull { i -> runCatching {
                val feature = features.getJSONObject(i)
                val geometry = feature.getJSONObject("geometry")
                if (geometry.optString("type") != "Point") return@runCatching null
                val coordinates = geometry.getJSONArray("coordinates")
                val properties = feature.getJSONObject("properties")
                val address = listOf("country", "state", "city", "district", "locality", "street", "housenumber")
                    .map { properties.optString(it).trim() }.filter { it.isNotEmpty() && it != "null" }.distinct().joinToString(" · ")
                val name = properties.optString("name").trim().takeUnless { it.isEmpty() || it == "null" } ?: address
                if (name.isBlank()) null else GeoPoint(coordinates.getDouble(1), coordinates.getDouble(0), name, address, "OpenStreetMap")
            }.getOrNull() }.distinctBy { it.coordinateKey() + it.name }
        }
    }
}
