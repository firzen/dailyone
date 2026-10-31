package com.firzen.dailyone

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class JournalBrowseUiTest {
    @get:Rule val compose = createComposeRule()
    private val media get() = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    @Test fun calendarSelectsExactlyTheChosenDaysEntries() {
        val day = LocalDate.now()
        val time = day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val first = Entry(createdAt = time, title = "日历测试")
        val second = Entry(createdAt = time + 1000, title = "同一天")
        var chosen: JournalCollection? = null
        compose.setContent { DailyOneTheme { JournalCalendar(listOf(first, second), media) { chosen = it } } }
        compose.onNodeWithText(day.dayOfMonth.toString()).performClick()
        compose.runOnIdle {
            assertEquals(day, chosen!!.day)
            assertEquals(setOf(first.id, second.id), chosen!!.ids.toSet())
        }
    }
    @Test fun collectionDisplaysCardsAndOpensTheSelectedEntry() {
        val entry = Entry(title = "集合标题", body = "集合正文", recordedLocation = GeoPoint(23.1, 113.3, "测试地点"))
        var opened: String? = null
        compose.setContent { DailyOneTheme {
            JournalCollectionSheet(JournalCollection("测试集合", listOf(entry.id)), listOf(entry), media, {}, { opened = it.id }, {})
        } }
        compose.onNodeWithText("测试集合").assertIsDisplayed()
        compose.onNodeWithText("集合正文").performClick()
        compose.runOnIdle { assertEquals(entry.id, opened) }
    }
}
