package com.firzen.dailyone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Fast map preview only. Editing and backfill continue to request a fresh fix. */
internal fun recentCurrentLocation(context: Context): GeoPoint? {
    val manager = context.getSystemService(LocationManager::class.java)
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!fine && !coarse) return null
    val now = android.os.SystemClock.elapsedRealtimeNanos()
    return manager.getProviders(true).mapNotNull { provider ->
        runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
    }.filter { location ->
        val age = now - location.elapsedRealtimeNanos
        age >= 0 && age <= java.util.concurrent.TimeUnit.MINUTES.toNanos(30)
    }.maxByOrNull { it.elapsedRealtimeNanos }?.let { location ->
        runCatching { GeoPoint(location.latitude, location.longitude) }.getOrNull()
    }
}

/** A fresh foreground fix only; historical journal backfill never calls this function. */
suspend fun currentLocation(context: Context): GeoPoint? = withTimeoutOrNull(20_000) {
    val manager = context.getSystemService(LocationManager::class.java)
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    require(fine || coarse) { "请允许定位权限" }
    val provider = when {
        manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
        fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
        else -> error("请开启系统定位")
    }
    suspendCancellableCoroutine { continuation ->
        if (Build.VERSION.SDK_INT >= 30) {
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                if (continuation.isActive) continuation.resume(location?.let { GeoPoint(it.latitude, it.longitude) })
            }
        } else {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    manager.removeUpdates(this)
                    if (continuation.isActive) continuation.resume(GeoPoint(location.latitude, location.longitude))
                }
                @Deprecated("Legacy callback") override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
            }
            manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            continuation.invokeOnCancellation { manager.removeUpdates(listener) }
        }
    }
}
