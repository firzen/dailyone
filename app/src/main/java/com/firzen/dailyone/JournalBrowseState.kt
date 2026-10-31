package com.firzen.dailyone

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*

/** Lives above the detail screen; native map views can still release their lifecycle resources. */
internal class JournalBrowseState {
    var calendar: LazyListState? = null
    var collection by mutableStateOf<JournalCollection?>(null)
    var collectionScroll = LazyListState()
    var places: PlaceService? = null
    val map = JournalMapState()
    fun selectCollection(value: JournalCollection) {
        collectionScroll = LazyListState()
        collection = value
    }
}
internal data class JournalMapCamera(val point: GeoPoint, val zoom: Float)
internal class JournalMapState {
    var initialized = false
    var requested = false
    var finishedAttempt = 0
    var recenter = true
    var resolvedReference: String? = null
    var loadedLocations: List<Pair<String, String>>? = null
    var loadedProvider: PlaceProvider? = null
    var loadedRetry = -1
    var camera: JournalMapCamera? = null
    var gps by mutableStateOf<GeoPoint?>(null)
    var center by mutableStateOf<GeoPoint?>(null)
    var userMoved by mutableStateOf(false)
    var locating by mutableStateOf(false)
    var locationError by mutableStateOf("")
    var locationAttempt by mutableIntStateOf(0)
    var provider by mutableStateOf<PlaceProvider?>(null)
    var automatic by mutableStateOf(PlaceProvider.GOOGLE)
    var points by mutableStateOf(emptyList<JournalMapPoint>())
    var completed by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var failures by mutableIntStateOf(0)
    var retry by mutableIntStateOf(0)
}
