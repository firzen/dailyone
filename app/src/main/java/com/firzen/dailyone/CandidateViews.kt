package com.firzen.dailyone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun CandidateCard(entry: Entry, enabled: Boolean, review: (Boolean) -> Unit, content: @Composable () -> Unit) {
    val latestReview by rememberUpdatedState(review)
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        when (value) {
            SwipeToDismissBoxValue.StartToEnd -> latestReview(true)
            SwipeToDismissBoxValue.EndToStart -> latestReview(false)
            SwipeToDismissBoxValue.Settled -> Unit
        }
        false // The database decides when to remove the overlay; errors leave the card intact.
    }, positionalThreshold = { it * .35f })
    SwipeToDismissBox(state = state, enableDismissFromStartToEnd = enabled, enableDismissFromEndToStart = enabled,
        backgroundContent = {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(19.dp)).background(if (state.dismissDirection == SwipeToDismissBoxValue.StartToEnd) Color(0xFF397B60) else Color(0xFFB84446)).padding(18.dp),
                contentAlignment = if (state.dismissDirection == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd) {
                Text(if (state.dismissDirection == SwipeToDismissBoxValue.StartToEnd) "保留为日记" else "删除候选", color = Color.White)
            }
        }) {
        Box {
            content()
            Box(Modifier.matchParentSize().clip(RoundedCornerShape(19.dp)).background(Color(0xFFB6323D).copy(alpha = .17f)))
            Row(Modifier.align(Alignment.BottomEnd).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("← 左滑删除 · 右滑保留", color = Color(0xFF8B2431), fontSize = 11.sp,
                    modifier = Modifier.background(Color(0xFFFFE6E8).copy(alpha = .94f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 3.dp))
            }
        }
    }
}

@Composable internal fun CandidatePhotoSelection(entry: Entry, media: File, dismiss: () -> Unit, confirm: (List<String>) -> Unit) {
    var chosen by remember(entry.id) { mutableStateOf(entry.photos.take(20).toSet()) }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(12.dp), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("选择保留的图片", style = MaterialTheme.typography.titleLarge)
                Text("已选 ${chosen.size} / 20 · 共 ${entry.photos.size} 张", Modifier.padding(vertical = 12.dp))
                LazyVerticalGrid(columns = GridCells.Adaptive(94.dp), modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(entry.photos, key = { it }) { name ->
                        Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp)).clickable {
                            chosen = if (name in chosen) chosen - name else if (chosen.size < 20) chosen + name else chosen
                        }) {
                            AsyncImage(File(media, name), "选择图片", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            Checkbox(checked = name in chosen, onCheckedChange = null, modifier = Modifier.align(Alignment.TopEnd))
                        }
                    }
                }
                Text("未选图片不加入正式日记，手机相册原图保留。", fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = dismiss) { Text("取消") }
                    Button(onClick = { confirm(entry.photos.filter { it in chosen }) }, enabled = chosen.isNotEmpty()) { Text("保存为正式日记") }
                }
            }
        }
    }
}
