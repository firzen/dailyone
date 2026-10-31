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

@Composable internal fun GoogleMapPanel(center: GeoPoint?, moved: (GeoPoint) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestMoved by rememberUpdatedState(moved)
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
                google.uiSettings.isZoomControlsEnabled = false
                google.uiSettings.isRotateGesturesEnabled = false
                google.uiSettings.isTiltGesturesEnabled = false
                google.uiSettings.isMapToolbarEnabled = false
                var userGesture = false
                google.setOnCameraMoveStartedListener { reason -> userGesture = reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE }
                google.setOnCameraIdleListener {
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
            disposed = true; lifecycle.removeObserver(observer)
            map?.setOnCameraMoveStartedListener(null); map?.setOnCameraIdleListener(null)
            stop(); view.onDestroy(); map = null
        }
    }
    LaunchedEffect(map, center?.coordinateKey()) {
        val point = center?.takeIf { it.coordinateSystem == CoordinateSystem.WGS84 } ?: return@LaunchedEffect
        map?.let { google -> google.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude, point.longitude), google.cameraPosition.zoom.coerceAtLeast(16f))) }
    }
    AndroidView(factory = { view }, modifier = modifier)
}
