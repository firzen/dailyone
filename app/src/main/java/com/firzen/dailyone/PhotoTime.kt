package com.firzen.dailyone

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

private val exifDateFormat = DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)
fun parsePhotoTime(value: String?, offset: String?, zone: ZoneId = ZoneId.systemDefault()): Long? {
    if (value.isNullOrBlank()) return null
    return runCatching {
        val local = LocalDateTime.parse(value.trim(), exifDateFormat)
        val zoneOffset = offset?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { ZoneOffset.of(it) }.getOrNull() }
        if (zoneOffset != null) local.toInstant(zoneOffset).toEpochMilli() else local.atZone(zone).toInstant().toEpochMilli()
    }.getOrNull()
}

enum class PhotoTimePreference(val label: String) {
    ASK("每次询问"), USE("使用图片时间"), KEEP("保留日记时间")
}
data class PhotoTimePrompt(val entryId: String, val timestamp: Long)
