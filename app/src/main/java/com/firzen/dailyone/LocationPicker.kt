package com.firzen.dailyone

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable internal fun LocationPicker(current: GeoPoint?, existing: GeoPoint?, results: List<GeoPoint>,
    locating: Boolean, searching: Boolean, error: String?, search: (String) -> Unit,
    retryCurrent: () -> Unit, select: (GeoPoint) -> Unit, close: () -> Unit,
    region: PlaceRegion = PlaceRegion.AUTO, city: String = "", changeRegion: (PlaceRegion) -> Unit = {}, changeCity: (String) -> Unit = {},
    androidKey: String = "", privacyAgreed: Boolean = false, agreePrivacy: () -> Unit = {},
    mapCenter: GeoPoint? = null, mapPick: GeoPoint? = null, nearby: List<GeoPoint> = emptyList(), resolvingMap: Boolean = false,
    focusMap: (GeoPoint) -> Unit = {}, moveMap: (GeoPoint) -> Unit = {}, distanceCenter: GeoPoint? = null,
    moreNearby: Boolean = false, loadingMoreNearby: Boolean = false, loadMoreNearby: () -> Unit = {}) {
    val effectiveRegion = if (region == PlaceRegion.AUTO) PlaceRegion.fromPoint(distanceCenter ?: existing ?: current) else region
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(existing ?: current) }
    var name by remember { mutableStateOf((existing ?: current)?.let { it.name.ifBlank { it.address } }.orEmpty()) }
    var userSelected by remember { mutableStateOf(false) }
    var preferCurrent by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(false) }
    var editCity by remember { mutableStateOf(false) }
    var cityInput by remember { mutableStateOf("") }
    fun choose(point: GeoPoint, focus: Boolean = true) {
        selected = point; name = point.name.ifBlank { point.address }; userSelected = true
        if (focus) { query = ""; focusMap(point) }
    }
    LaunchedEffect(current) { if (current != null && !userSelected && (existing == null || preferCurrent)) { selected = current; name = current.name.ifBlank { current.address } } }
    LaunchedEffect(mapPick) { if (mapPick != null) choose(mapPick, false) }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = close) { Icon(Icons.Outlined.Close, "取消选择位置") }
                    Text("选择位置", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    TextButton(enabled = selected != null && name.isNotBlank() && !resolvingMap && effectiveRegion?.provider != PlaceProvider.GOOGLE, onClick = {
                        selected?.let { point -> select(point.copy(name = name.trim(), nameOrigin = if (name.trim() != point.name) PlaceNameOrigin.CUSTOM else PlaceNameOrigin.POI)) }
                    }) { Text("使用此位置") }
                }
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp), singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium, placeholder = { Text("搜索地点、店名或地址", fontSize = 14.sp) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); search(query) }),
                    trailingIcon = { IconButton(onClick = { keyboard?.hide(); search(query) }, enabled = !searching && query.isNotBlank()) { Icon(Icons.Outlined.Search, "搜索地点") } })
                Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    var menu by remember { mutableStateOf(false) }
                    Box(Modifier.weight(1f)) {
                        TextButton(onClick = { menu = true }) { Text(if (region == PlaceRegion.AUTO) "自动 · ${effectiveRegion?.label ?: "地区"}" else region.label, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Outlined.ArrowDropDown, null, Modifier.size(18.dp)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) { PlaceRegion.entries.forEach { item ->
                            DropdownMenuItem(text = { Text(item.label) }, onClick = { menu = false; changeRegion(item) })
                        } }
                    }
                    val area = if (region == PlaceRegion.AUTO) PlaceRegion.fromPoint(distanceCenter ?: existing ?: current) else region
                    if (area == PlaceRegion.MAINLAND || area == null) TextButton(onClick = { cityInput = city; editCity = true }) {
                        Icon(Icons.Outlined.LocationCity, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(city.ifBlank { "搜索城市" }, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 112.dp))
                        Icon(Icons.Outlined.ArrowDropDown, null, Modifier.size(18.dp))
                    }
                }
                Box(Modifier.fillMaxWidth().weight(.9f).heightIn(min = 130.dp).background(MaterialTheme.colorScheme.surfaceContainer)) {
                    when {
                        effectiveRegion?.provider == PlaceProvider.GOOGLE -> Text("Google 地图将后续接入，请切换到大陆、香港或澳门", Modifier.align(Alignment.Center).padding(24.dp), fontSize = 13.sp)
                        androidKey.isBlank() -> Text("在设置中填写高德 Android平台 Key 后显示地图", Modifier.align(Alignment.Center).padding(24.dp), fontSize = 13.sp)
                        !privacyAgreed -> Column(Modifier.align(Alignment.Center).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("地图由高德提供，用于浏览地图和拖动选点。启用前请阅读高德隐私政策。", fontSize = 13.sp)
                            TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://lbs.amap.com/pages/privacy/"))) } }) { Text("高德隐私政策") }
                            Button(onClick = agreePrivacy) { Text("同意并启用地图") }
                        }
                        else -> {
                            AmapMapPanel(androidKey, mapCenter, { point -> selected = null; name = ""; query = ""; userSelected = true; moveMap(point) }, Modifier.fillMaxSize())
                            Icon(Icons.Outlined.LocationOn, "地图中心选点", Modifier.align(Alignment.Center).size(38.dp).offset(y = (-18).dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    FilledTonalIconButton(onClick = { preferCurrent = true; userSelected = false; selected = current; name = current?.name.orEmpty(); query = ""; retryCurrent() }, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp), enabled = !locating) {
                        Icon(Icons.Outlined.MyLocation, "回到当前位置")
                    }
                }
                if (searching || resolvingMap) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error != null) Text(error, Modifier.padding(horizontal = 16.dp, vertical = 5.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                LazyColumn(Modifier.fillMaxWidth().weight(1.1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)) {
                    item {
                        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (results.isNotEmpty()) "搜索结果 · 由近到远" else "附近地点", Modifier.weight(1f).padding(start = 4.dp), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            if (selected != null) TextButton(onClick = { editName = !editName }) { Text("修改店名", fontSize = 12.sp) }
                        }
                        if (locating) Text("正在获取当前位置…", Modifier.padding(horizontal = 4.dp), fontSize = 11.sp)
                        if (editName && selected != null) OutlinedTextField(name, { name = it; userSelected = true }, label = { Text("地点名称 / 店名") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    }
                    if (results.isEmpty() && existing != null) item { PlaceChoice(existing, selected, "日记现有位置") { choose(existing) } }
                    val candidates = if (results.isNotEmpty()) results else nearby
                    val ordered = if (distanceCenter != null && candidates.all { it.coordinateSystem == distanceCenter.coordinateSystem }) nearestPlaces(candidates, distanceCenter) else candidates
                    if (!resolvingMap && !locating && error == null && results.isEmpty() && nearby.isEmpty()) item {
                        Text("附近暂无地点，可拖动地图或搜索", Modifier.padding(6.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(ordered) { point ->
                        val distance = distanceCenter?.takeIf { it.coordinateSystem == point.coordinateSystem }?.let { distanceLabel(distanceMeters(point, it)) }.orEmpty()
                        PlaceChoice(point, selected, distance) { choose(point) }
                    }
                    if (results.isEmpty() && moreNearby) item {
                        TextButton(onClick = loadMoreNearby, enabled = !loadingMoreNearby, modifier = Modifier.fillMaxWidth()) {
                            Text(if (loadingMoreNearby) "正在加载…" else "加载更多地点")
                        }
                    }
                }
            }
        }
        if (editCity) AlertDialog(onDismissRequest = { editCity = false }, title = { Text("搜索城市") },
            text = { OutlinedTextField(cityInput, { cityInput = it }, singleLine = true, label = { Text("城市名称") }, placeholder = { Text("留空使用选点所在城市") }) },
            confirmButton = { TextButton(onClick = { changeCity(cityInput.trim()); editCity = false }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { editCity = false }) { Text("取消") } })
    }
}

@Composable private fun PlaceChoice(point: GeoPoint, selected: GeoPoint?, prefix: String, choose: () -> Unit) {
    val checked = selected?.let { it.sameCoordinates(point) && it.name == point.name } == true
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = choose).padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(checked, onClick = null, modifier = Modifier.padding(horizontal = 8.dp).size(20.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(point.label(), Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (prefix.isNotBlank()) Text(prefix, Modifier.padding(start = 8.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            }
            if (point.address.isNotBlank() && point.address != point.name) Text(point.address, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
