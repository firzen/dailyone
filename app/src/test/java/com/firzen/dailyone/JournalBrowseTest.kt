package com.firzen.dailyone

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class JournalBrowseTest {
    @Test fun calendarUsesLocalDayAndExcludesUnknownAndDeletedEntries() {
        val entry = Entry(createdAt = Instant.parse("2024-02-29T20:00:00Z").toEpochMilli())
        val groups = journalDays(listOf(entry, entry.copy(id = "unknown", scanDay = "unknown"), entry.copy(id = "deleted", deleted = true)), ZoneId.of("Asia/Shanghai"))
        assertEquals(listOf(entry), groups[LocalDate.of(2024, 3, 1)])
        assertEquals(1, groups.size)
    }
    @Test fun leapFebruaryAndSundayEndingMonthHaveCompleteMondayFirstWeeks() {
        val leap = monthCells(YearMonth.of(2024, 2))
        assertEquals(35, leap.size)
        assertEquals(3, leap.indexOf(LocalDate.of(2024, 2, 1)))
        assertEquals(29, leap.filterNotNull().size)
        val sunday = monthCells(YearMonth.of(2024, 3))
        assertEquals(LocalDate.of(2024, 3, 31), sunday.last())
    }
    @Test fun nearbyMarkersSplitWhenZoomedInButSameLocationPreservesAllEntries() {
        val points = listOf(JournalMapPoint(GeoPoint(0.0, .001), listOf("a", "b")), JournalMapPoint(GeoPoint(0.0, .002), listOf("c")))
        assertEquals(listOf("a", "b", "c"), journalClusters(points, 2f).single().ids)
        assertEquals(2, journalClusters(points, 20f).size)
        val duplicates = points + points.first()
        assertEquals(3, journalClusters(duplicates, 2f).single().ids.size)
    }
    @Test fun polesAndDateLineRemainFinite() {
        val points = listOf(JournalMapPoint(GeoPoint(90.0, 180.0), listOf("n")), JournalMapPoint(GeoPoint(-90.0, -180.0), listOf("s")))
        assertEquals(2, journalClusters(points, 4f).size)
        assertTrue(journalClusters(emptyList(), 4f).isEmpty())
    }
}
