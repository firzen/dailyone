package com.firzen.dailyone

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class JournalSplitIntegrationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun splitPersistsBothRecordsAndUndoRestoresSource() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "split-${UUID.randomUUID()}"
        val store = JournalStore(context, "$name.db", name)
        try {
            store.load()
            val photos = List(2) { "${UUID.randomUUID()}.jpg" }
            val source = Entry(photos = photos, photoLocations = mapOf(photos[0] to GeoPoint(31.0, 121.0, "原地点")))
            store.save(source)
            val before = requireNotNull(store.get(source.id))
            val action = store.split(source.id, setOf(photos[0]))
            val created = action.last().second
            assertEquals(2, store.entries.value.count { !it.deleted })
            assertEquals(before.location, store.get(source.id)?.location)
            assertEquals(before.location, store.get(created.id)?.location)
            store.load()
            assertEquals(created, store.get(created.id))
            assertTrue(store.restoreBatchIfUnchanged(action))
            assertEquals(before, store.get(source.id))
            assertTrue(requireNotNull(store.get(created.id)).deleted)
            val second = store.split(source.id, setOf(photos[0]))
            store.save(second.last().second.copy(body = "后续编辑"))
            assertFalse(store.restoreBatchIfUnchanged(second))
            assertEquals("后续编辑", store.get(second.last().second.id)?.body)
        } finally {
            store.close(); context.deleteDatabase("$name.db"); store.mediaDir.deleteRecursively()
        }
    }

    @Test fun pickerRequiresSelectionAndPreservesContent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = Entry(photos = listOf("a", "b"))
        var result: Set<String>? = null
        compose.setContent { DailyOneTheme {
            JournalSplitPicker(entry, context.cacheDir, true, {}, { result = it })
        } }
        compose.onNodeWithText("拆出 0 张图片").assertIsNotEnabled()
        compose.onNodeWithContentDescription("图片 1").performClick()
        compose.onNodeWithText("拆出 1 张图片").assertIsEnabled()
        compose.onNodeWithText("全选").performClick()
        compose.onNodeWithText("拆出 2 张图片").assertIsNotEnabled()
        compose.onNodeWithText("请为原日记至少保留文字、图片或视频").assertIsDisplayed()
        compose.onNodeWithText("取消全选").performClick()
        compose.onNodeWithContentDescription("图片 2").performClick()
        compose.onNodeWithText("拆出 1 张图片").performClick()
        compose.runOnIdle { assertEquals(setOf("b"), result) }
    }
}
