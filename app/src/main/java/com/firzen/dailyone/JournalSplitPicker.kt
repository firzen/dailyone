package com.firzen.dailyone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File

@Composable internal fun JournalSplitPicker(entry: Entry, media: File, ready: Boolean,
    close: () -> Unit, split: (Set<String>) -> Unit) {
    var selected by rememberSaveable(entry.id) { mutableStateOf(arrayListOf<String>()) }
    val chosen = selected.filter { it in entry.photos }.toSet()
    val leavesContent = chosen.size < entry.photos.size || entry.title.isNotBlank() || entry.body.isNotBlank() || entry.videos.isNotEmpty()
    BackHandler { if (ready) close() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = close, enabled = ready) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "取消拆分") }
            Text("拆分日记", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { selected = if (chosen.size == entry.photos.size) arrayListOf() else ArrayList(entry.photos) }, enabled = ready) {
                Text(if (chosen.size == entry.photos.size) "取消全选" else "全选")
            }
        }
        Text("选择要移到新日记的图片，时间、地点和日记本保持一致。", Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
        LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.weight(1f), contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(entry.photos, key = { _, name -> name }) { index, name ->
                val checked = name in chosen
                Box(Modifier.aspectRatio(1f).toggleable(value = checked, enabled = ready, role = Role.Checkbox, onValueChange = {
                    selected = ArrayList(if (checked) chosen - name else chosen + name)
                })) {
                    AsyncImage(File(media, name), "图片 ${index + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Checkbox(checked, onCheckedChange = null, modifier = Modifier.align(Alignment.TopEnd))
                }
            }
        }
        if (!leavesContent) Text("请为原日记至少保留文字、图片或视频", Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.error)
        Button(onClick = { split(chosen) }, enabled = ready && chosen.isNotEmpty() && leavesContent,
            modifier = Modifier.fillMaxWidth().padding(18.dp)) { Text(if (ready) "拆出 ${chosen.size} 张图片" else "正在拆分…") }
    }
}
