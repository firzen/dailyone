package com.firzen.dailyone

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable internal fun WebMapButton(provider: PlaceProvider, point: GeoPoint?, query: String = "") {
    val context = LocalContext.current
    TextButton(onClick = {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webMapUrl(provider, point, query)))) }
        catch (_: android.content.ActivityNotFoundException) { Toast.makeText(context, "未找到可打开地图的浏览器", Toast.LENGTH_SHORT).show() }
    }) { Text(if (query.isNotBlank()) "在网页搜索" else "网页查看位置") }
}

@Composable internal fun WebMapPanel(provider: PlaceProvider, point: GeoPoint?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text("已使用网页地图备用方案")
        Text("可查看位置；网页选点无法自动回填。")
        if (point?.coordinateSystem == CoordinateSystem.GCJ02 && provider == PlaceProvider.GOOGLE) Text("此位置为高德坐标，使用高德网页查看。")
        WebMapButton(provider, point)
    }
}

@Composable internal fun WebLocationPicker(current: GeoPoint?, existing: GeoPoint?, locating: Boolean, error: String?,
    provider: PlaceProvider, changeProvider: (PlaceProvider) -> Unit, retryCurrent: () -> Unit, select: (GeoPoint) -> Unit, close: () -> Unit) {
    var useCurrent by remember { mutableStateOf(existing == null) }
    val point = if (useCurrent) current else existing
    var name by remember(point) { mutableStateOf(point?.name.orEmpty()) }
    var query by remember { mutableStateOf("") }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.statusBarsPadding().navigationBarsPadding().imePadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("选择位置", style = MaterialTheme.typography.titleLarge)
                Row { PlaceProvider.entries.forEach { item -> TextButton(onClick = { changeProvider(item) }, enabled = provider != item) { Text(item.label) } } }
                WebMapPanel(provider, point)
                OutlinedTextField(query, { query = it }, label = { Text("搜索地点、店名或地址") }, modifier = Modifier.fillMaxWidth())
                if (query.isNotBlank()) WebMapButton(provider, null, query)
                Text("保存当前位置或日记原有位置，可手动填写名称。")
                if (existing != null) TextButton(onClick = { useCurrent = false }) { Text("使用日记现有位置") }
                TextButton(onClick = { useCurrent = true; retryCurrent() }, enabled = !locating) { Text(if (locating) "正在定位…" else "获取当前位置") }
                point?.let { Text(it.coordinates()) }
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
                OutlinedTextField(name, { name = it }, label = { Text("地点名称") }, modifier = Modifier.fillMaxWidth())
                Button(enabled = point != null, onClick = { point?.let { select(it.copy(name = name.trim().ifBlank { it.name.ifBlank { "已记录位置" } }, nameOrigin = if (name.trim().isNotBlank() && name.trim() != it.name) PlaceNameOrigin.CUSTOM else it.nameOrigin)) } }) { Text("使用此位置") }
                TextButton(onClick = close) { Text("取消") }
            }
        }
    }
}
