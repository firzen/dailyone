package com.firzen.dailyone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MyLocation
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

internal enum class JournalView(val label: String) { LIST("列表"), CALENDAR("日历"), MAP("地图") }
internal data class JournalCollection(val title: String, val ids: List<String>, val day: LocalDate? = null, val location: GeoPoint? = null)

@Composable internal fun JournalCalendar(entries: List<Entry>, media: File, browse: JournalBrowseState? = null, choose: (JournalCollection) -> Unit) {
    val days = remember(entries) { journalDays(entries) }
    val today = LocalDate.now()
    val months = remember(days, today) {
        val first = days.keys.minOrNull()?.let(YearMonth::from) ?: YearMonth.from(today)
        val last = maxOf(days.keys.maxOrNull()?.let(YearMonth::from) ?: first, YearMonth.from(today))
        generateSequence(last) { if (it > first) it.minusMonths(1) else null }.toList()
    }
    val latestMonth = days.keys.maxOrNull()?.let(YearMonth::from)
    val initialMonth = months.indexOf(latestMonth).coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = initialMonth + if (initialMonth > 0 && entries.any { it.scanDay == "unknown" }) 1 else 0)
    val retainedState = browse?.calendar ?: state.also { browse?.calendar = it }
    LazyColumn(state = retainedState, modifier = Modifier.fillMaxSize().testTag("journal-calendar"), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 96.dp)) {
        if (entries.any { it.scanDay == "unknown" }) item(key = "unknown") {
            TextButton(onClick = { choose(JournalCollection("日期待确认", entries.filter { it.scanDay == "unknown" }.map { it.id })) }) { Text("查看日期待确认的日记") }
        }
        items(months, key = { it.toString() }) { month ->
            Text("${month.year}年${month.monthValue}月", Modifier.padding(horizontal = 8.dp, vertical = 18.dp), fontSize = 24.sp)
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            monthCells(month).chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    week.forEach { day ->
                        val group = days[day].orEmpty()
                        val photo = group.firstNotNullOfOrNull { it.photos.firstOrNull() }
                        val video = if (photo == null) group.firstNotNullOfOrNull { it.videos.firstOrNull() } else null
                        Box(Modifier.weight(1f).aspectRatio(.86f).padding(bottom = 3.dp).clip(RoundedCornerShape(5.dp))
                            .then(if (day != null) Modifier.background(if (day == today) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                                .clickable { choose(JournalCollection(day.format(DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.SIMPLIFIED_CHINESE)), group.map { it.id }, day = day)) } else Modifier), contentAlignment = Alignment.Center) {
                            if (photo != null) AsyncImage(File(media, photo), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            else if (video != null) VideoThumbnail(File(media, video), Modifier.fillMaxSize())
                            if (photo != null || video != null) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .28f)))
                            day?.let { Text(it.dayOfMonth.toString(), fontSize = 23.sp, fontWeight = if (group.isNotEmpty()) FontWeight.Bold else FontWeight.Normal,
                                color = if (photo != null || video != null) Color.White else MaterialTheme.colorScheme.onSurface) }
                            if (group.isNotEmpty()) Text(if (group.size > 1) "${group.size} 篇" else "•", Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp), fontSize = 10.sp,
                                color = if (photo != null || video != null) Color.White else MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun JournalCollectionSheet(collection: JournalCollection, entries: List<Entry>, media: File, close: () -> Unit, open: (Entry) -> Unit, create: () -> Unit, scroll: LazyListState? = null) {
    val listState = scroll ?: rememberLazyListState()
    val chosen = entries.filter { it.id in collection.ids }.sortedByDescending { it.createdAt }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("journal-collection"), state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(collection.title, fontSize = 24.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${chosen.size} 篇日记", Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = create, modifier = Modifier.padding(top = 16.dp)) { Text("新建日记条目") }
                }
            }
            if (chosen.isEmpty()) item { Text("这一天还没有日记", Modifier.fillMaxWidth().padding(24.dp)) }
            items(chosen, key = { it.id }) { entry ->
                Surface(onClick = { open(entry) }, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (entry.title.isNotBlank()) Text(entry.title, fontSize = 20.sp)
                        if (entry.body.isNotBlank()) Text(entry.body, fontSize = 16.sp, lineHeight = 25.sp, maxLines = 8, overflow = TextOverflow.Ellipsis)
                        entry.attachments.firstOrNull()?.let { name ->
                            if (name in entry.photos) AsyncImage(File(media, name), "日记照片", Modifier.fillMaxWidth().heightIn(max = 300.dp).aspectRatio(1.3f).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                            else VideoThumbnail(File(media, name), Modifier.fillMaxWidth().height(220.dp))
                        }
                        val time = java.time.Instant.ofEpochMilli(entry.createdAt).atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy/M/d HH:mm"))
                        Text((if (entry.scanDay == "unknown") "日期待确认" else time) + (entry.location?.let { " · ${it.label()}" } ?: "") + if (entry.candidate) " · 待确认" else "",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(Modifier.padding(top = 16.dp))
            }
        }
    }
}

@Composable internal fun JournalMap(entries: List<Entry>, service: PlaceService, androidKey: String, privacyAgreed: Boolean, agreePrivacy: () -> Unit, state: JournalMapState, choose: (JournalCollection) -> Unit) {
    val context = LocalContext.current
    val located = remember(entries) { entries.filter { it.location != null && !it.deleted } }
    var gps by state::gps
    var center by state::center
    var userMoved by state::userMoved
    var locating by state::locating
    var locationError by state::locationError
    var locationAttempt by state::locationAttempt
    if (!state.initialized) {
        gps = recentCurrentLocation(context)
        val first = located.firstOrNull()?.location
        state.automatic = if (first?.coordinateSystem == CoordinateSystem.GCJ02) PlaceProvider.AMAP else PlaceProvider.defaultFor(first)
        state.initialized = true
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) locationAttempt++
        else locationError = "未允许定位，显示日记位置"
    }
    val locate: () -> Unit = {
        userMoved = false
        state.recenter = true
        center = null
        locationError = ""
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) locationAttempt++
        else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    LaunchedEffect(Unit) { if (!state.requested) { state.requested = true; locate() } }
    LaunchedEffect(locationAttempt) {
        if (locationAttempt == 0 || state.finishedAttempt == locationAttempt) return@LaunchedEffect
        locating = true
        try {
            recentCurrentLocation(context)?.let { gps = it }
            val point = currentLocation(context)
            if (point != null) gps = point
            else if (gps == null) locationError = "暂未获取到当前位置，显示日记位置"
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { locationError = "定位不可用，请检查系统定位设置" }
        finally { locating = false }
        state.finishedAttempt = locationAttempt
    }
    var provider by state::provider
    var automatic by state::automatic
    val reference = gps ?: located.firstOrNull()?.location
    LaunchedEffect(reference?.coordinateKey()) {
        reference?.let { point ->
            if (state.resolvedReference == point.coordinateKey()) return@LaunchedEffect
            try { automatic = if (point.coordinateSystem == CoordinateSystem.GCJ02) PlaceProvider.AMAP else service.defaultProvider(point) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Keep the known-region default when system geocoding is unavailable. */ }
            state.resolvedReference = point.coordinateKey()
        }
    }
    val active = provider ?: automatic
    var points by state::points
    val locations = remember(located) { located.groupBy { it.location!!.coordinateKey() }.values.toList() }
    var completed by state::completed
    var loading by state::loading
    var failures by state::failures
    var retry by state::retry
    val ready = if (active == PlaceProvider.AMAP) androidKey.isNotBlank() && privacyAgreed else BuildConfig.GOOGLE_MAPS_CONFIGURED
    LaunchedEffect(gps, active, ready, locationAttempt) {
        val point = gps ?: return@LaunchedEffect
        if (!ready || (!state.recenter && state.camera != null)) return@LaunchedEffect
        try {
            val mapped = service.mapCoordinate(point, active)
            if (!userMoved) { center = mapped; state.recenter = false }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { locationError = "当前位置转换失败，可重试定位或切换地图服务" }
    }
    LaunchedEffect(located, active, ready, retry) {
        val signature = located.map { it.id to it.location!!.coordinateKey() }
        if (!ready || (signature == state.loadedLocations && active == state.loadedProvider && retry == state.loadedRetry)) return@LaunchedEffect
        if (active != state.loadedProvider) points = emptyList()
        failures = 0; completed = 0
        loading = true
        val converted = mutableListOf<JournalMapPoint>()
        try {
            for (batch in locations.chunked(40)) {
                try {
                    val mapped = service.mapCoordinates(batch.map { it.first().location!! }, active)
                    batch.zip(mapped).forEach { (group, point) -> converted += JournalMapPoint(point, group.map { it.id }) }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { failures += batch.size }
                completed += batch.size
                points = converted.toList()
            }
            state.loadedLocations = signature
            state.loadedProvider = active
            state.loadedRetry = retry
        } finally { loading = false }
    }
    val pick: (List<String>) -> Unit = { ids ->
        val selected = located.filter { it.id in ids }
        val cities = selected.mapNotNull { it.location?.city?.takeIf(String::isNotBlank) }.distinct()
        val label = if (cities.size == 1) cities.single() else selected.firstOrNull()?.location?.label() ?: "地点日记"
        choose(JournalCollection(label, ids, location = selected.firstOrNull()?.location))
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${located.size} 篇有位置 · ${entries.size - located.size} 篇无位置", Modifier.weight(1f), fontSize = 12.sp)
            PlaceProvider.entries.forEach { item -> TextButton(onClick = { userMoved = false; state.recenter = true; state.camera = null; provider = item }, enabled = active != item) { Text(item.label) } }
        }
        if (locationError.isNotBlank()) Text(locationError, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                active == PlaceProvider.AMAP && androidKey.isBlank() -> Text("高德地图暂不可用，可切换 Google 地图", Modifier.align(Alignment.Center).padding(24.dp))
                active == PlaceProvider.AMAP && !privacyAgreed -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("地图由高德提供，用于浏览日记位置。启用前请阅读高德隐私政策。")
                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://lbs.amap.com/pages/privacy/"))) }) { Text("高德隐私政策") }
                    Button(onClick = agreePrivacy) { Text("同意并启用地图") }
                }
                active == PlaceProvider.GOOGLE && !BuildConfig.GOOGLE_MAPS_CONFIGURED -> Text("Google 地图暂不可用，可切换高德地图", Modifier.align(Alignment.Center).padding(24.dp))
                else -> key(active) {
                    if (active == PlaceProvider.AMAP) AmapMapPanel(androidKey, center?.takeIf { it.coordinateSystem == CoordinateSystem.GCJ02 }, { userMoved = true }, Modifier.fillMaxSize(), points = points, pick = pick, fitPoints = !userMoved && gps == null && state.camera == null, initialCamera = state.camera, cameraChanged = { if (center != null || points.isNotEmpty() || userMoved) state.camera = it })
                    else GoogleMapPanel(center?.takeIf { it.coordinateSystem == CoordinateSystem.WGS84 }, { userMoved = true }, Modifier.fillMaxSize(), points = points, pick = pick, fitPoints = !userMoved && gps == null && state.camera == null, initialCamera = state.camera, cameraChanged = { if (center != null || points.isNotEmpty() || userMoved) state.camera = it })
                }
            }
            FilledTonalIconButton(onClick = locate, enabled = !locating, modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 36.dp)) {
                if (locating) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.MyLocation, "回到当前位置")
            }
            if (loading) Surface(Modifier.align(Alignment.TopCenter).padding(12.dp), shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("正在加载日记位置 $completed/${locations.size}", fontSize = 12.sp)
                }
            }
            if (failures > 0 && !loading) Surface(Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp), shape = RoundedCornerShape(12.dp)) {
                TextButton(onClick = { retry++ }) { Text("${failures} 个位置暂未显示，点击重试") }
            }
        }
    }
}
