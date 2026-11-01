package com.firzen.dailyone

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class JournalScrollUiTest {
    @get:Rule val compose = createComposeRule()
    private fun render(state: LazyListState) {
        val media = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val entries = (0..89).map { Entry(title = "记录 $it", body = "不同高度的日记内容\n".repeat(1 + it % 7), createdAt = 1_780_000_000_000L - it * 86_400_000L) }
        compose.setContent { DailyOneTheme {
            val scope = rememberCoroutineScope()
            JournalList(state, entries, false, false, true, media, {}, {}, {}, {}, ScanProgress(), {}, {}, { _, _ -> }, emptySet(), {}, {}, {}, {},
                { NotebookMenu(emptyList(), null, true, {}, { _, _ -> }, onDoubleClick = { scope.launch { state.animateScrollToItem(0) }; Unit }) },
                emptyMap(), JournalBrowseState(), JournalView.LIST, {}, "", false, {}, {})
        } }
    }
    @Test fun doubleTapHeaderAndTitleReturnToTopWithoutOpeningNotebookMenu() {
        val state = LazyListState()
        render(state)
        compose.onNodeWithTag("journal-list").performScrollToIndex(60)
        compose.onNodeWithTag("journal-list-header").performTouchInput { doubleClick(center) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, state.firstVisibleItemIndex); assertEquals(0, state.firstVisibleItemScrollOffset) }
        compose.onNodeWithTag("journal-list").performScrollToIndex(55)
        compose.onNodeWithText("全部日记").performTouchInput { doubleClick() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, state.firstVisibleItemIndex) }
        compose.onNodeWithText("新建日记本").assertDoesNotExist()
        compose.onNodeWithText("全部日记").performClick()
        compose.waitUntil(5000) { runCatching { compose.onNodeWithText("新建日记本").assertIsDisplayed(); true }.getOrDefault(false) }
    }
    @Test fun draggingScrollbarReachesBothEndsOfVariableHeightList() {
        val state = LazyListState()
        render(state)
        compose.onNodeWithTag("journal-list").performScrollToIndex(40)
        compose.onNodeWithTag("journal-list").performTouchInput { swipeUp(durationMillis = 120) }
        compose.waitForIdle()
        compose.onNodeWithTag("journal-fast-scroll").assertIsDisplayed()
        val track = compose.onNodeWithTag("journal-fast-scroll-track").fetchSemanticsNode().boundsInRoot
        var thumb = compose.onNodeWithTag("journal-fast-scroll").fetchSemanticsNode().boundsInRoot
        val down = track.bottom - thumb.center.y + thumb.height / 2
        compose.onNodeWithTag("journal-fast-scroll").performTouchInput {
            swipe(center, Offset(center.x, center.y + down), durationMillis = 400)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(state.canScrollForward); assertTrue(state.firstVisibleItemIndex > 40) }
        thumb = compose.onNodeWithTag("journal-fast-scroll").fetchSemanticsNode().boundsInRoot
        val up = thumb.center.y - track.top + thumb.height / 2
        compose.onNodeWithTag("journal-fast-scroll").performTouchInput {
            swipe(center, Offset(center.x, center.y - up), durationMillis = 400)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, state.firstVisibleItemIndex); assertEquals(0, state.firstVisibleItemScrollOffset) }
    }
}
