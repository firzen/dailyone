package com.firzen.dailyone

import kotlin.math.abs

/** Invert the official GPS-to-map conversion, checking convergence rather than relabelling GCJ02. */
internal suspend fun invertMapCoordinate(target: GeoPoint, forward: suspend (GeoPoint) -> GeoPoint): GeoPoint {
    require(target.coordinateSystem == CoordinateSystem.GCJ02)
    var guess = target.copy(coordinateSystem = CoordinateSystem.WGS84)
    repeat(6) {
        val mapped = forward(guess)
        require(mapped.coordinateSystem == CoordinateSystem.GCJ02)
        val latitudeError = mapped.latitude - target.latitude
        val longitudeError = mapped.longitude - target.longitude
        if (abs(latitudeError) <= .000001 && abs(longitudeError) <= .000001) return guess
        guess = guess.copy(latitude = guess.latitude - latitudeError, longitude = guess.longitude - longitudeError)
    }
    error("位置坐标转换未收敛，请稍后重试")
}
