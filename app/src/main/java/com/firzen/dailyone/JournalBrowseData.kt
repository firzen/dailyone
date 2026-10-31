package com.firzen.dailyone

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.*

internal fun journalDays(entries: List<Entry>, zone: ZoneId = ZoneId.systemDefault()): Map<LocalDate, List<Entry>> =
    entries.filter { !it.deleted && it.scanDay != "unknown" }
        .groupBy { Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() }

/** Monday first, including empty cells before and after the month. */
internal fun monthCells(month: YearMonth): List<LocalDate?> {
    val offset = month.atDay(1).dayOfWeek.value - 1
    val count = ((offset + month.lengthOfMonth() + 6) / 7) * 7
    return (0 until count).map { if (it in offset until offset + month.lengthOfMonth()) month.atDay(it - offset + 1) else null }
}

internal data class JournalMapPoint(val point: GeoPoint, val ids: List<String>)

/** Screen-sized Mercator grid: zooming in separates nearby entries; identical locations stay together. */
internal fun journalClusters(points: List<JournalMapPoint>, zoom: Float): List<JournalMapPoint> {
    val world = 256.0 * 2.0.pow(zoom.toDouble())
    return points.groupBy {
        val lat = Math.toRadians(it.point.latitude.coerceIn(-85.05112878, 85.05112878))
        val x = ((it.point.longitude + 180) / 360 * world) % world
        val y = (1 - ln(tan(lat) + 1 / cos(lat)) / PI) / 2 * world
        floor(x / 64).toLong() to floor(y / 64).toLong()
    }.values.map { group -> JournalMapPoint(group.first().point, group.flatMap { it.ids }.distinct()) }
}
