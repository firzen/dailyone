package com.firzen.dailyone

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.TextureMapView
import com.amap.api.maps.model.CameraPosition
import com.amap.api.maps.model.LatLng
import kotlin.math.abs

/** Only composed after the user has accepted the SDK privacy notice. */
@Composable internal fun AmapMapPanel(androidKey: String, center: GeoPoint?, moved: (GeoPoint) -> Unit, modifier: Modifier = Modifier, points: List<JournalMapPoint> = emptyList(), pick: (List<String>) -> Unit = {}, fitPoints: Boolean = true, initialCamera: JournalMapCamera? = null, cameraChanged: (JournalMapCamera) -> Unit = {}) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestMoved by rememberUpdatedState(moved)
    val latestCameraChanged by rememberUpdatedState(cameraChanged)
    val restore = remember { initialCamera }
    var restored by remember { mutableStateOf(false) }
    val latestPoints by rememberUpdatedState(points)
    val latestPick by rememberUpdatedState(pick)
    fun drawMarkers(map: AMap) {
        map.clear()
        journalClusters(latestPoints, map.cameraPosition.zoom).forEach { cluster ->
            map.addMarker(com.amap.api.maps.model.MarkerOptions()
                .position(LatLng(cluster.point.latitude, cluster.point.longitude))
                .icon(com.amap.api.maps.model.BitmapDescriptorFactory.fromBitmap(journalMarkerBitmap(cluster.ids.size, context.resources.displayMetrics.density)))
                .anchor(.5f, .5f)).`object` = cluster.ids
        }
    }
    var ignoredTarget by remember { mutableStateOf<LatLng?>(null) }
    val view = remember(androidKey, context) {
        runCatching {
            MapsInitializer.updatePrivacyShow(context, true, true)
            MapsInitializer.updatePrivacyAgree(context, true)
            MapsInitializer.setApiKey(androidKey)
            MapsInitializer.setProtocol(MapsInitializer.HTTPS)
            MapsInitializer.initialize(context.applicationContext)
            TextureMapView(context).apply { onCreate(null) }
        }.getOrNull()
    }
    if (view == null) { Box(modifier, contentAlignment = Alignment.Center) { Text("地图加载失败，请稍后重试") }; return }
    DisposableEffect(view, lifecycle) {
        val map = view.map
        map.uiSettings.isZoomControlsEnabled = false
        map.uiSettings.isRotateGesturesEnabled = false
        map.uiSettings.isTiltGesturesEnabled = false
        map.uiSettings.isScaleControlsEnabled = true
        map.setOnMarkerClickListener { marker ->
            @Suppress("UNCHECKED_CAST")
            val ids = marker.`object` as? List<String>
            ids?.let(latestPick)
            true
        }
        var userGesture = false
        map.setOnMapTouchListener { event -> if (event?.actionMasked == android.view.MotionEvent.ACTION_DOWN) userGesture = true }
        map.setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
            override fun onCameraChange(position: CameraPosition?) = Unit
            override fun onCameraChangeFinish(position: CameraPosition?) {
                if (latestPoints.isNotEmpty()) drawMarkers(map)
                val target = position?.target ?: return
                latestCameraChanged(JournalMapCamera(GeoPoint(target.latitude, target.longitude, coordinateSystem = CoordinateSystem.GCJ02), position.zoom))
                val ignored = ignoredTarget
                ignoredTarget = null
                if (ignored != null && abs(target.latitude - ignored.latitude) < .00001 && abs(target.longitude - ignored.longitude) < .00001) { userGesture = false; return }
                if (!userGesture) return
                userGesture = false
                latestMoved(GeoPoint(target.latitude, target.longitude, coordinateSystem = CoordinateSystem.GCJ02))
            }
        })
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_RESUME -> view.onResume()
            Lifecycle.Event.ON_PAUSE -> view.onPause()
            else -> Unit
        } }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onResume()
        onDispose {
            map.cameraPosition?.let { camera -> latestCameraChanged(JournalMapCamera(GeoPoint(camera.target.latitude, camera.target.longitude, coordinateSystem = CoordinateSystem.GCJ02), camera.zoom)) }
            lifecycle.removeObserver(observer); map.setOnMarkerClickListener(null); map.setOnCameraChangeListener(null); map.setOnMapTouchListener(null); view.onPause(); view.onDestroy() }
    }
    LaunchedEffect(view, center?.coordinateKey()) {
        if (!restored) {
            restored = true
            restore?.takeIf { it.point.coordinateSystem == CoordinateSystem.GCJ02 }?.let {
                val target = LatLng(it.point.latitude, it.point.longitude)
                ignoredTarget = target
                view.map.moveCamera(CameraUpdateFactory.newLatLngZoom(target, it.zoom))
                return@LaunchedEffect
            }
        }
        if (center != null) {
            require(center.coordinateSystem == CoordinateSystem.GCJ02)
            val target = LatLng(center.latitude, center.longitude)
            ignoredTarget = target
            val zoom = view.map.cameraPosition?.zoom?.coerceAtLeast(16f) ?: 16f
            view.map.animateCamera(CameraUpdateFactory.newLatLngZoom(target, zoom))
        }
    }
    LaunchedEffect(view, points) {
        if (points.isEmpty()) { view.map.clear(); return@LaunchedEffect }
        drawMarkers(view.map)
        if (!fitPoints || center != null) return@LaunchedEffect
        if (points.size == 1) view.map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(points.first().point.latitude, points.first().point.longitude), 14f))
        else {
            val bounds = com.amap.api.maps.model.LatLngBounds.builder()
            points.forEach { bounds.include(LatLng(it.point.latitude, it.point.longitude)) }
            view.map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels / 2, (48 * context.resources.displayMetrics.density).toInt()))
        }
    }
    AndroidView(factory = { view }, modifier = modifier)
}
