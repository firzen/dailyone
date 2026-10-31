package com.firzen.dailyone

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class PhotoTimeTest {
    @Test fun originalOffsetTakesPriorityOverPhoneTimezone() {
        assertEquals(Instant.parse("2006-05-23T08:03:14Z").toEpochMilli(), parsePhotoTime("2006:05:23 16:03:14", "+08:00", ZoneId.of("UTC")))
    }
    @Test fun missingOffsetUsesLocalTimezone() {
        assertEquals(Instant.parse("2006-05-23T08:03:14Z").toEpochMilli(), parsePhotoTime("2006:05:23 16:03:14", null, ZoneId.of("Asia/Shanghai")))
    }
    @Test fun unusableDatesDoNotChangeDiaryTime() {
        listOf(null, "", "0000:00:00 00:00:00", "2024:02:30 10:00:00", "not a date").forEach {
            assertNull(parsePhotoTime(it, null))
        }
    }
    @Test fun invalidOffsetFallsBackToPhoneTimezone() {
        assertEquals(Instant.parse("2024-01-01T12:00:00Z").toEpochMilli(), parsePhotoTime("2024:01:01 12:00:00", "broken", ZoneId.of("UTC")))
    }
}
