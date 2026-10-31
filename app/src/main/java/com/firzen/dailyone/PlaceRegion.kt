package com.firzen.dailyone

import java.util.Locale

enum class PlaceProvider { AMAP, GOOGLE }
enum class PlaceRegion(val label: String, val countryCode: String) {
    AUTO("自动（当前位置 / 日记位置）", ""), MAINLAND("中国大陆", "CN"),
    HONG_KONG("香港", "HK"), MACAO("澳门", "MO"), OTHER("其他地区", "");
    val provider get() = if (this == OTHER) PlaceProvider.GOOGLE else PlaceProvider.AMAP
    companion object {
        fun fromPoint(point: GeoPoint?): PlaceRegion? = when (point?.countryCode?.uppercase(Locale.ROOT)) {
            "CN" -> when { point.city.startsWith("香港") -> HONG_KONG; point.city.startsWith("澳门") -> MACAO; else -> MAINLAND }
            "HK" -> HONG_KONG
            "MO" -> MACAO
            null, "" -> null
            else -> OTHER
        }
        fun resolve(requested: PlaceRegion, near: GeoPoint?): PlaceRegion = if (requested != AUTO) requested
            else fromPoint(near) ?: throw IllegalArgumentException("暂未识别搜索地区，请手动选择中国大陆、香港、澳门或其他地区")
    }
}
