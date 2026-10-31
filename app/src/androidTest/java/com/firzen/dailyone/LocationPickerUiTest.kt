package com.firzen.dailyone

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LocationPickerUiTest {
    @get:Rule val compose = createComposeRule()
    private val current = GeoPoint(23.1, 113.3, "当前位置公园", "当前地址")
    @Test fun defaultsToCurrentPlaceAndDoesNotSaveUntilConfirmed() {
        var saved: GeoPoint? = null
        compose.setContent { MaterialTheme { LocationPicker(current, null, emptyList(), false, false, null,
            {}, {}, { saved = it }, {}) } }
        compose.waitForIdle()
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithText("使用此位置").performClick()
        compose.runOnIdle { assertEquals(current, saved) }
    }
    @Test fun selectedSearchResultSupportsCustomShopNameWithoutMovingIt() {
        val result = GeoPoint(24.0, 114.0, "河边咖啡", "测试路2号")
        var saved: GeoPoint? = null
        compose.setContent { MaterialTheme { LocationPicker(current, null, listOf(result), false, false, null,
            {}, {}, { saved = it }, {}) } }
        compose.onNodeWithText("河边咖啡").performScrollTo().performClick()
        compose.onNodeWithText("地点名称 / 店名").performScrollTo().performTextReplacement("河边咖啡旗舰店")
        compose.onNodeWithText("使用此位置").performClick()
        compose.runOnIdle { assertEquals(result.copy(name = "河边咖啡旗舰店"), saved) }
    }
}
