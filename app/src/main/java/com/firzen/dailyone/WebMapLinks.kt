package com.firzen.dailyone

import java.net.URLEncoder

/** Keep the stored coordinate system; never send GCJ02 as GPS to Google. */
internal fun webMapUrl(provider: PlaceProvider, point: GeoPoint? = null, query: String = ""): String {
    fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    val amap = provider == PlaceProvider.AMAP || (query.isBlank() && point?.coordinateSystem == CoordinateSystem.GCJ02)
    if (query.isNotBlank()) return if (amap) "https://uri.amap.com/search?keyword=${encode(query.trim())}&callnative=0"
        else "https://www.google.com/maps/search/?api=1&query=${encode(query.trim())}"
    if (point == null) return if (amap) "https://www.amap.com/" else "https://www.google.com/maps/"
    return if (amap) "https://uri.amap.com/marker?position=${point.longitude},${point.latitude}&name=${encode(point.label())}&coordinate=${if (point.coordinateSystem == CoordinateSystem.WGS84) "wgs84" else "gaode"}&callnative=0"
        else "https://www.google.com/maps/search/?api=1&query=${point.latitude},${point.longitude}"
}
