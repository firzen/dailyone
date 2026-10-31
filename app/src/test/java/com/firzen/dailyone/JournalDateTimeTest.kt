package com.firzen.dailyone

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class JournalDateTimeTest {
    @Test fun chosenDateAndMinuteRespectDeviceTimeZone() {
        val time = journalTimestamp(2024, 2, 29, 23, 45, ZoneId.of("Asia/Shanghai"))
        assertEquals(Instant.parse("2024-02-29T15:45:00Z").toEpochMilli(), time)
        val original = Entry(title = "保留", body = "正文", recordedLocation = GeoPoint(1.0, 2.0, "店名"), candidate = true, scanDay = "unknown")
        val updated = original.withJournalTime(time)
        assertEquals(time, updated.createdAt); assertEquals("2024-02-29", updated.scanDay)
        assertEquals(original.body, updated.body); assertEquals(original.location, updated.location)
        assertTrue(updated.candidate)
    }
    @Test fun impossibleCalendarDateIsRejected() {
        assertThrows(java.time.DateTimeException::class.java) { journalTimestamp(2025, 2, 29, 12, 0) }
    }
}
