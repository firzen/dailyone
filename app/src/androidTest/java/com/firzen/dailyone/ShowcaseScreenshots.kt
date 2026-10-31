package com.firzen.dailyone

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** Explicit screenshot tooling: synthetic entries, separate cache files, no journal database writes. */
class ShowcaseScreenshots {
    @get:Rule val compose = createComposeRule()
    @Test fun captureProductScreens() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("showcase") == "true")
        val context = instrumentation.targetContext
        val media = File(context.cacheDir, "showcase-media").apply { mkdirs() }
        val out = File(context.cacheDir, "showcase-output").apply { mkdirs() }
        listOf("lake", "forest", "coffee").forEach { name ->
            instrumentation.context.assets.open("showcase/$name.jpg").use { input -> File(media, "$name.jpg").outputStream().use { input.copyTo(it) } }
        }
        fun point(lat: Double, lng: Double, name: String) = GeoPoint(lat, lng, name, city = "杭州市", countryCode = "CN", coordinateSystem = CoordinateSystem.GCJ02)
        val sites = listOf(point(30.250,120.147,"湖畔步道"), point(30.257,120.159,"街角咖啡馆"), point(30.243,120.141,"周末散步"), point(30.262,120.145,"林间小路"))
        val titles = listOf("把日子过慢一点", "一杯咖啡的时间", "走进绿色里", "普通的一天，也值得记下", "周末，留给自己", "今天的小确幸", "傍晚的一段路", "换个角度看日常", "好好吃一顿早餐", "给生活留一点空白", "那些微小的快乐", "从今天开始记录")
        val bodies = listOf("沿着湖边走了很久。风吹过来的时候，突然觉得，安安静静地待一会儿就很好。", "找了个靠窗的位置，读完了搁置很久的几页书。没有赶时间，咖啡也刚刚好。", "阳光落在树叶上，脚步慢下来，听见风，也听见自己。", "没发生什么特别的事。但认真做了一顿饭，也给朋友打了电话。")
        val dates = listOf(31,29,27,25,23,21,18,16,12,10,7,3)
        val entries = titles.mapIndexed { i, title -> Entry(createdAt = LocalDate.of(2026,10,dates[i]).atTime(9+i%10, 15).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), title = title, body = bodies[i%4], photos = if(i%4==3) emptyList() else listOf(listOf("lake.jpg","coffee.jpg","forest.jpg")[i%3]), recordedLocation = sites[i%sites.size]) }
        val demoEntries = entries + entries.last().copy(id = java.util.UUID.randomUUID().toString(), createdAt = LocalDate.of(2026,9,24).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
        val only = InstrumentationRegistry.getArguments().getString("screen")
        val books = listOf(Notebook(DEFAULT_NOTEBOOK, "生活片段"))
        val browse = JournalBrowseState()
        val key = MapSettings(context).loadAndroidKey()
        val map = browse.map
        map.initialized = true; map.requested = true; map.recenter = false
        map.provider = PlaceProvider.AMAP; map.automatic = PlaceProvider.AMAP
        map.center = sites.first(); map.camera = JournalMapCamera(sites.first(), 14f)
        map.resolvedReference = sites.first().coordinateKey()
        map.points = entries.groupBy { it.location!!.coordinateKey() }.values.map { JournalMapPoint(it.first().location!!, it.map { e -> e.id }) }
        map.loadedLocations = entries.map { it.id to it.location!!.coordinateKey() }; map.loadedProvider = PlaceProvider.AMAP; map.loadedRetry = 0
        var screenLifecycle: androidx.lifecycle.Lifecycle? = null
        var screen by mutableStateOf(only ?: "timeline")
        compose.setContent { DailyOneTheme {
            val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
            SideEffect { screenLifecycle = lifecycle }
            val view = LocalView.current
            DisposableEffect(view) { val old = view.keepScreenOn; view.keepScreenOn = true; onDispose { view.keepScreenOn = old } }
            Surface(Modifier.fillMaxSize()) {
                if(screen == "collection") JournalCollectionSheet(JournalCollection("湖畔步道", demoEntries.take(4).map { it.id }), demoEntries, media, {}, {}, {})
                else if(screen == "entry") Editor(entries.first().copy(body = "早上沿着湖边走了很久，没有目的地。\n\n风吹过来的时候，突然觉得，能这样安安静静地待一会儿就很好。"), media, true, false, {}, {}, {}, {})
                else JournalList(rememberLazyListState(), demoEntries, false, false, true, media, {}, {}, {}, {}, ScanProgress(), {}, {}, { _, _ -> }, emptySet(), {}, {}, {}, {},
                    { TextButton(onClick = {}) { Text("生活片段 ▾", color = androidx.compose.ui.graphics.Color.White) } }, books.associate { it.id to it.name }, browse,
                    when(screen) { "calendar" -> JournalView.CALENDAR; "map" -> JournalView.MAP; else -> JournalView.LIST }, {}, key, true, {}, {})
            }
        } }
        fun capture(name: String, delay: Long = 900) {
            compose.waitForIdle(); android.os.SystemClock.sleep(delay)
            compose.waitUntil(30_000) { screenLifecycle?.currentState?.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) == true }
            val roots = compose.onAllNodes(isRoot())
            val node = if(name == "collection") compose.onNode(isDialog()) else roots[0]
            val crop = node.captureToImage().asAndroidBitmap()
            File(out, "$name.png").outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
            crop.recycle()
        }
        if(only != null) { capture(only, if(only == "map") 6000 else 900); return }
        capture("timeline")
        compose.runOnIdle { screen = "calendar" }; capture("calendar")
        compose.runOnIdle { screen = "entry" }; capture("entry")
        compose.runOnIdle { screen = "collection" }; capture("collection")
        compose.runOnIdle { browse.collection = null; screen = "map" }
        if(key.isNotBlank()) capture("map", 6000)
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("showcase_directory", out.path) })
    }
}
