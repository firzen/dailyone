package com.firzen.dailyone

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class GoogleMapUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun liveMapRendersTilesWhenExplicitlyRequested() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live-google-map") == "true")
        assertTrue("Map credential is missing from this build", BuildConfig.GOOGLE_MAPS_CONFIGURED)
        val loaded = AtomicBoolean(false)
        val center = GeoPoint(35.6812, 139.7671, countryCode = "JP")
        compose.setContent { MaterialTheme {
            val view = LocalView.current
            DisposableEffect(view) {
                val previous = view.keepScreenOn; view.keepScreenOn = true
                onDispose { view.keepScreenOn = previous }
            }
            GoogleMapPanel(center, {}, Modifier.fillMaxSize()) { loaded.set(true) }
        } }
        compose.waitUntil(60_000) { loaded.get() }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(instrumentation.targetContext.cacheDir, "google-map-live.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("google_map_rendered", "true") })
    }
}
