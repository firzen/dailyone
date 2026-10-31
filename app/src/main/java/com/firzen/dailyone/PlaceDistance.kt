package com.firzen.dailyone

import kotlin.math.*

internal fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
    require(a.coordinateSystem == b.coordinateSystem) { "计算距离前须统一坐标系" }
    val lat1 = Math.toRadians(a.latitude); val lat2 = Math.toRadians(b.latitude)
    val dLat = lat2 - lat1; val dLon = Math.toRadians(b.longitude - a.longitude)
    val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
    return 6_371_008.8 * 2 * atan2(sqrt(h.coerceIn(0.0, 1.0)), sqrt((1 - h).coerceIn(0.0, 1.0)))
}
internal fun nearestPlaces(points: List<GeoPoint>, center: GeoPoint): List<GeoPoint> = points.sortedBy { distanceMeters(it, center) }
internal fun distanceLabel(meters: Double): String = if (meters < 1000) "${meters.roundToInt()}米" else String.format(java.util.Locale.SIMPLIFIED_CHINESE, "%.1f公里", meters / 1000)

/** POI identity crosses coordinate systems; legacy locations need both a name and local evidence. */
internal fun samePlace(a: GeoPoint, b: GeoPoint): Boolean {
    if (a.poiId.isNotBlank() && b.poiId.isNotBlank()) return a.poiId == b.poiId
    if (a.name.isBlank() || a.name.trim() != b.name.trim()) return false
    if (a.address.isNotBlank() && a.address.trim() == b.address.trim()) return true
    return a.coordinateSystem == b.coordinateSystem && distanceMeters(a, b) <= 25
}
