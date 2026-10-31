package com.firzen.dailyone

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
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
        compose.runOnIdle { assertEquals(current.copy(nameOrigin = PlaceNameOrigin.POI), saved) }
    }
    @Test fun selectedSearchResultSupportsCustomShopNameWithoutMovingIt() {
        val result = GeoPoint(24.0, 114.0, "河边咖啡", "测试路2号")
        var saved: GeoPoint? = null
        compose.setContent { MaterialTheme { LocationPicker(current, null, listOf(result), false, false, null,
            {}, {}, { saved = it }, {}) } }
        compose.onNodeWithText("河边咖啡").performScrollTo().performClick()
        compose.onNodeWithText("修改店名").performScrollTo().performClick()
        compose.onNodeWithText("地点名称 / 店名").performScrollTo().performTextReplacement("河边咖啡旗舰店")
        compose.onNodeWithText("使用此位置").performClick()
        compose.runOnIdle { assertEquals(result.copy(name = "河边咖啡旗舰店", nameOrigin = PlaceNameOrigin.CUSTOM), saved) }
    }
    @Test fun existingLocationStaysSelectedWhenCurrentLocationArrives() {
        val existing = GeoPoint(24.0, 114.0, "日记里的餐厅", "日记地址")
        val gps = mutableStateOf<GeoPoint?>(null)
        var saved: GeoPoint? = null
        compose.setContent { MaterialTheme { LocationPicker(gps.value, existing, emptyList(), false, false, null,
            {}, {}, { saved = it }, {}) } }
        compose.runOnIdle { gps.value = current }
        compose.onNodeWithText("当前位置").assertDoesNotExist()
        compose.onNodeWithText(current.name).assertDoesNotExist()
        compose.onNodeWithText("使用此位置").performClick()
        compose.runOnIdle { assertEquals(existing.copy(nameOrigin = PlaceNameOrigin.POI), saved) }
    }
    @Test fun nearbyShopsFollowChangedMapSelection() {
        val first = GeoPoint(23.1, 113.3, "初始位置附近咖啡店")
        val next = GeoPoint(24.0, 114.0, "新位置附近餐厅")
        val pick = mutableStateOf<GeoPoint?>(null)
        val nearby = mutableStateOf(listOf(first))
        var saved: GeoPoint? = null
        compose.setContent { MaterialTheme { LocationPicker(current, current, emptyList(), false, false, null,
            {}, {}, { saved = it }, {}, mapPick = pick.value, nearby = nearby.value) } }
        compose.onNodeWithText(first.name).assertExists()
        compose.runOnIdle { pick.value = next; nearby.value = listOf(next) }
        compose.onNodeWithText(first.name).assertDoesNotExist()
        compose.onAllNodesWithText(next.name).onFirst().assertExists()
        compose.onNodeWithText("使用此位置").performClick()
        compose.runOnIdle { assertEquals(next.copy(nameOrigin = PlaceNameOrigin.POI), saved) }
    }
    @Test fun reachingBottomLoadsNextPageWithoutAButton() {
        val places = mutableStateOf((0 until 25).map { current.copy(name = "地点 $it", poiId = "fixture-$it") })
        val more = mutableStateOf(true)
        var requests = 0
        compose.setContent { MaterialTheme { LocationPicker(current, null, emptyList(), false, false, null,
            {}, {}, {}, {}, nearby = places.value, moreNearby = more.value, loadMoreNearby = {
                requests++; places.value = places.value + current.copy(name = "下一页地点", poiId = "next"); more.value = false
            }) } }
        compose.runOnIdle { assertEquals(0, requests) }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(25)
        compose.onNode(hasScrollToIndexAction()).performTouchInput { swipeUp() }
        compose.waitUntil { requests == 1 }
        compose.onNodeWithText("下一页地点").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("加载更多地点").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, requests) }
    }
    @Test fun existingPlaceAppearsOnceAndOtherNearbyPlacesRemain() {
        val existing = current.copy(poiId = "existing")
        val nearby = listOf(existing.copy(coordinateSystem = CoordinateSystem.GCJ02), current.copy(name = "另一个地点", poiId = "other"))
        compose.setContent { MaterialTheme { LocationPicker(current, existing, emptyList(), false, false, null,
            {}, {}, {}, {}, nearby = nearby) } }
        compose.onAllNodesWithText(existing.name).assertCountEquals(1)
        compose.onNodeWithText("日记现有位置").assertExists()
        compose.onNodeWithText("另一个地点").assertExists()
    }
    @Test fun googleLocationCanBeConfirmedWithoutConvertingCoordinates() {
        val overseas = GeoPoint(35.68, 139.76, "东京公园", source = "Google", countryCode = "JP", poiId = "google-fixture")
        var saved: GeoPoint? = null
        compose.setContent { MaterialTheme { LocationPicker(null, overseas, emptyList(), false, false, null,
            {}, {}, { saved = it }, {}, distanceCenter = overseas, googleConfigured = false) } }
        compose.onNodeWithText("Google 地图 Key 尚未配置，请配置后更新安装包").assertExists()
        compose.onNodeWithText("使用此位置").performClick()
        compose.runOnIdle { assertEquals(overseas.copy(nameOrigin = PlaceNameOrigin.POI), saved) }
    }

}
