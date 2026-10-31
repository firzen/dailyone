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
    @Test fun leftDeletesRightKeepsAndOverlayDoesNotBlockOpening() {
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
        compose.runOnIdle { assertEquals(listOf(false), decisions) }
        compose.onNodeWithTag("candidate").performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(false, true), decisions) }
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

}
