package com.firzen.dailyone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun LocationPicker(current: GeoPoint?, existing: GeoPoint?, results: List<GeoPoint>,
    locating: Boolean, searching: Boolean, error: String?, search: (String) -> Unit,
    retryCurrent: () -> Unit, select: (GeoPoint) -> Unit, close: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    fun submit(query: String) { keyboard?.hide(); search(query) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<GeoPoint?>(null) }
    var name by remember { mutableStateOf("") }
    var userSelected by remember { mutableStateOf(false) }
    fun choose(point: GeoPoint) { selected = point; name = point.name.ifBlank { point.address }; userSelected = true }
    LaunchedEffect(current) {
        if (current != null && (!userSelected || (selected?.sameCoordinates(current) == true && name.isBlank()))) { selected = current; name = current.name.ifBlank { current.address } }
    }
    AlertDialog(onDismissRequest = close, title = { Text("选择位置") },
        text = { Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("搜索地点、店名或地址") }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit(query) }), trailingIcon = {
                    IconButton(onClick = { submit(query) }, enabled = !searching && query.isNotBlank()) { Icon(Icons.Outlined.Search, "搜索地点") }
                })
            Text("当前位置", fontWeight = FontWeight.Medium)
            if (current != null) PlaceChoice(current, selected, "当前位置", { choose(current) })
            if (locating) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(if (current == null) "正在获取当前位置…" else "正在解析地点名称…", Modifier.padding(start = 8.dp), fontSize = 13.sp)
            }
            TextButton(onClick = retryCurrent, enabled = !locating) { Text(if (current == null) "重新获取当前位置" else "刷新当前位置") }
            if (existing != null && (current == null || !existing.sameCoordinates(current))) {
                Text("日记现有位置", fontWeight = FontWeight.Medium)
                PlaceChoice(existing, selected, "现有位置", { choose(existing) })
            }
            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (results.isNotEmpty()) {
                Text("搜索结果", fontWeight = FontWeight.Medium)
                results.forEach { point -> PlaceChoice(point, selected, "搜索结果", { choose(point) }) }
            }
            if (error != null) Text(error, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
            if (selected != null) {
                HorizontalDivider()
                OutlinedTextField(name, { name = it; userSelected = true }, label = { Text("地点名称 / 店名") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Text("可修改显示名称，坐标保持所选地点的位置。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } },
        confirmButton = { TextButton(enabled = selected != null && name.isNotBlank(), onClick = {
            selected?.let { select(it.copy(name = name.trim())) }
        }) { Text("使用此位置") } },
        dismissButton = { TextButton(onClick = close) { Text("取消") } })
}

@Composable private fun PlaceChoice(point: GeoPoint, selected: GeoPoint?, fallback: String, choose: () -> Unit) {
    val checked = selected?.let { it.sameCoordinates(point) && it.name == point.name } == true
    Row(Modifier.fillMaxWidth().clickable(onClick = choose).padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = checked, onClick = choose)
        Column(Modifier.weight(1f)) {
            Text(point.name.ifBlank { point.address }.ifBlank { fallback }, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            val detail = point.address.takeIf { it.isNotBlank() && it != point.name } ?: if (point.name.isBlank()) "可填写地点名称" else ""
            if (detail.isNotBlank()) Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Outlined.LocationOn, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
    }
}
