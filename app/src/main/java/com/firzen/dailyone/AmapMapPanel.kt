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
@Composable internal fun AmapMapPanel(androidKey: String, center: GeoPoint?, moved: (GeoPoint) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestMoved by rememberUpdatedState(moved)
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
    if (view == null) { Box(modifier, contentAlignment = Alignment.Center) { Text("地图加载失败，请检查 Android平台 Key") }; return }
    DisposableEffect(view, lifecycle) {
        val map = view.map
        map.uiSettings.isZoomControlsEnabled = false
        map.uiSettings.isRotateGesturesEnabled = false
        map.uiSettings.isTiltGesturesEnabled = false
        map.uiSettings.isScaleControlsEnabled = true
        var userGesture = false
        map.setOnMapTouchListener { event -> if (event?.actionMasked == android.view.MotionEvent.ACTION_DOWN) userGesture = true }
        map.setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
            override fun onCameraChange(position: CameraPosition?) = Unit
            override fun onCameraChangeFinish(position: CameraPosition?) {
                val target = position?.target ?: return
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
        onDispose { lifecycle.removeObserver(observer); map.setOnCameraChangeListener(null); map.setOnMapTouchListener(null); view.onPause(); view.onDestroy() }
    }
    LaunchedEffect(view, center?.coordinateKey()) {
        if (center != null) {
            require(center.coordinateSystem == CoordinateSystem.GCJ02)
            val target = LatLng(center.latitude, center.longitude)
            ignoredTarget = target
            val zoom = view.map.cameraPosition?.zoom?.coerceAtLeast(16f) ?: 16f
            view.map.animateCamera(CameraUpdateFactory.newLatLngZoom(target, zoom))
        }
    }
    AndroidView(factory = { view }, modifier = modifier)
}
