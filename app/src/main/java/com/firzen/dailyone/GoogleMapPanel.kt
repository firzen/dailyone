package com.firzen.dailyone

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.LatLng

@Composable internal fun GoogleMapPanel(center: GeoPoint?, moved: (GeoPoint) -> Unit, modifier: Modifier = Modifier, points: List<JournalMapPoint> = emptyList(), pick: (List<String>) -> Unit = {}, fitPoints: Boolean = true, initialCamera: JournalMapCamera? = null, cameraChanged: (JournalMapCamera) -> Unit = {}, onLoaded: () -> Unit = {}) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestMoved by rememberUpdatedState(moved)
    val latestCameraChanged by rememberUpdatedState(cameraChanged)
    val restore = remember { initialCamera }
    var restored by remember { mutableStateOf(false) }
    val latestLoaded by rememberUpdatedState(onLoaded)
    val latestPoints by rememberUpdatedState(points)
    val latestPick by rememberUpdatedState(pick)
    fun drawMarkers(google: GoogleMap) {
        google.clear()
        journalClusters(latestPoints, google.cameraPosition.zoom).forEach { cluster ->
            google.addMarker(com.google.android.gms.maps.model.MarkerOptions()
                .position(LatLng(cluster.point.latitude, cluster.point.longitude))
                .icon(com.google.android.gms.maps.model.BitmapDescriptorFactory.fromBitmap(journalMarkerBitmap(cluster.ids.size, context.resources.displayMetrics.density)))
                .anchor(.5f, .5f))?.tag = cluster.ids
        }
    }
    val available = remember(context) { GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS }
    if (!available) {
        Box(modifier, contentAlignment = Alignment.Center) { Text("请安装或更新 Google Play 服务后使用地图") }; return
    }
    var map by remember { mutableStateOf<GoogleMap?>(null) }
    val view = remember(context) { MapView(context).apply { onCreate(null) } }
    DisposableEffect(view, lifecycle) {
        var disposed = false
        view.getMapAsync { google ->
            if (!disposed) {
                map = google
                google.setOnMapLoadedCallback { latestLoaded() }
                google.uiSettings.isZoomControlsEnabled = false
                google.uiSettings.isRotateGesturesEnabled = false
                google.uiSettings.isTiltGesturesEnabled = false
                google.uiSettings.isMapToolbarEnabled = false
                var userGesture = false
                google.setOnCameraMoveStartedListener { reason -> userGesture = reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE }
                google.setOnMarkerClickListener { marker ->
                    @Suppress("UNCHECKED_CAST")
                    val ids = marker.tag as? List<String>
                    ids?.let(latestPick)
                    true
                }
                google.setOnCameraIdleListener {
                    val camera = google.cameraPosition
                    latestCameraChanged(JournalMapCamera(GeoPoint(camera.target.latitude, camera.target.longitude, source = "Google"), camera.zoom))
                    drawMarkers(google)
                    if (userGesture) {
                        userGesture = false
                        val target = google.cameraPosition.target
                        latestMoved(GeoPoint(target.latitude, target.longitude, source = "Google"))
                    }
                }
            }
        }
        var started = false
        var resumed = false
        fun resume() { if (!started) { view.onStart(); started = true }; if (!resumed) { view.onResume(); resumed = true } }
        fun pause() { if (resumed) { view.onPause(); resumed = false } }
        fun stop() { pause(); if (started) { view.onStop(); started = false } }
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_START -> if (!started) { view.onStart(); started = true }
            Lifecycle.Event.ON_RESUME -> resume()
            Lifecycle.Event.ON_PAUSE -> pause()
            Lifecycle.Event.ON_STOP -> stop()
            else -> Unit
        } }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { view.onStart(); started = true }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resume()
        onDispose {
            map?.cameraPosition?.let { camera -> latestCameraChanged(JournalMapCamera(GeoPoint(camera.target.latitude, camera.target.longitude, source = "Google"), camera.zoom)) }
            disposed = true; lifecycle.removeObserver(observer)
            map?.setOnMarkerClickListener(null)
            map?.setOnCameraMoveStartedListener(null); map?.setOnCameraIdleListener(null); map?.setOnMapLoadedCallback(null)
            stop(); view.onDestroy(); map = null
        }
    }
    LaunchedEffect(map, center?.coordinateKey()) {
        val google = map ?: return@LaunchedEffect
        if (!restored) {
            restored = true
            restore?.takeIf { it.point.coordinateSystem == CoordinateSystem.WGS84 }?.let {
                google.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.point.latitude, it.point.longitude), it.zoom))
                return@LaunchedEffect
            }
        }
        val point = center?.takeIf { it.coordinateSystem == CoordinateSystem.WGS84 } ?: return@LaunchedEffect
        google.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude, point.longitude), google.cameraPosition.zoom.coerceAtLeast(16f)))
    }
    LaunchedEffect(map, points) {
        val google = map ?: return@LaunchedEffect
        if (points.isEmpty()) { google.clear(); return@LaunchedEffect }
        drawMarkers(google)
        if (!fitPoints || center != null) return@LaunchedEffect
        if (points.size == 1) google.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(points.first().point.latitude, points.first().point.longitude), 14f))
        else {
            val bounds = com.google.android.gms.maps.model.LatLngBounds.builder()
            points.forEach { bounds.include(LatLng(it.point.latitude, it.point.longitude)) }
            google.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels / 2, (48 * context.resources.displayMetrics.density).toInt()))
        }
    }
    AndroidView(factory = { view }, modifier = modifier)
}
