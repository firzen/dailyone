package com.firzen.dailyone

import androidx.compose.foundation.clickable
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual swipe overlay on an isolated test Activity, with no journal database. */
@RunWith(AndroidJUnit4::class)
class CandidateGestureTest {
    @get:Rule val compose = createComposeRule()
    @Test fun leftKeepsRightDeletesAndOverlayDoesNotBlockOpening() {
        val decisions = mutableListOf<Boolean>()
        var opened = false
        compose.setContent { MaterialTheme {
            CandidateCard(Entry(candidate = true), true, { decisions += it }) {
                Box(Modifier.fillMaxWidth().height(140.dp).testTag("candidate").clickable { opened = true })
            }
        } }
        compose.onNodeWithTag("candidate").performClick()
        compose.runOnIdle { assertTrue(opened) }
        compose.onNodeWithTag("candidate").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(true), decisions) }
        compose.onNodeWithTag("candidate").performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(true, false), decisions) }
    }
    @Test fun gesturesUseUpdatedCandidateCallbackDuringScanning() {
        val revision = mutableStateOf(1)
        var reviewedRevision = 0
        compose.setContent { MaterialTheme {
            val current by revision
            CandidateCard(Entry(candidate = true), true, { reviewedRevision = current }) {
                Box(Modifier.fillMaxWidth().height(140.dp).testTag("candidate"))
            }
        } }
        compose.runOnIdle { revision.value = 2 }
        compose.onNodeWithTag("candidate").performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(2, reviewedRevision) }
    }
    @Test fun narrowPhotoTileHasAnUnobstructedDeleteButton() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "gallery-ui-${UUID.randomUUID()}").apply { mkdirs() }
        val photos = listOf("one.jpg", "two.jpg", "three.jpg")
        listOf(96 to 192, 240 to 100, 96 to 192).forEachIndexed { i, (width, height) ->
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            File(dir, photos[i]).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            bitmap.recycle()
        }
        var removed: String? = null
        try {
            compose.setContent { MaterialTheme { PhotoGallery(photos, dir, true, { removed = it }, { _, _ -> }) } }
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("删除图片 1").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("删除图片 1").performTouchInput { click() }
            compose.runOnIdle { assertEquals("one.jpg", removed) }
        } finally { dir.deleteRecursively() }
    }

    @Test fun photosCanBeDraggedInReadingMode() = checkPhotoDrag(false)
    @Test fun photosCanBeDraggedInEditingMode() = checkPhotoDrag(true)
    private fun checkPhotoDrag(editing: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "drag-ui-${UUID.randomUUID()}").apply { mkdirs() }
        val original = listOf("first.jpg", "second.jpg")
        original.forEach { name ->
            val bitmap = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
            File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            bitmap.recycle()
        }
        val photos = mutableStateOf(original)
        val moves = mutableListOf<Pair<Int, Int>>()
        try {
            compose.setContent { MaterialTheme {
                PhotoGallery(photos.value, dir, editing, {}, { from, to ->
                    moves += from to to
                    val next = photos.value.toMutableList(); next.add(to, next.removeAt(from)); photos.value = next
                })
            } }
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("图片 2，查看大图").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("图片 1，查看大图").performTouchInput { click() }
            compose.onNodeWithContentDescription("关闭大图").assertExists().performClick()
            val first = compose.onNodeWithContentDescription("图片 1，查看大图").fetchSemanticsNode().boundsInRoot
            val second = compose.onNodeWithContentDescription("图片 2，查看大图").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithContentDescription("图片 1，查看大图").performTouchInput {
                down(center)
                advanceEventTime(700)
                moveTo(second.center - first.topLeft, delayMillis = 350)
                up()
            }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(listOf(0 to 1), moves)
                assertEquals(original.reversed(), photos.value)
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun longPressSelectsJournalThroughCandidateOverlayWithoutOpeningIt() {
        val selected = mutableStateOf(false)
        var opened = false
        val entry = Entry(title = "多选测试条目", candidate = true, scanDay = "2024-01-01")
        compose.setContent { MaterialTheme {
            val card: @androidx.compose.runtime.Composable () -> Unit = {
                JournalCard(entry, File("/"), true,
                    { if (selected.value) selected.value = false else opened = true }, { selected.value = true })
            }
            if (selected.value) card() else CandidateCard(entry, true, {}) { card() }
        } }
        compose.onNodeWithText("多选测试条目").performTouchInput { longClick() }
        compose.runOnIdle { assertTrue(selected.value); assertFalse(opened) }
        compose.onNodeWithText("多选测试条目").performTouchInput { click() }
        compose.runOnIdle { assertFalse(selected.value); assertFalse(opened) }
    }

}
