package com.firzen.dailyone

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable internal fun PhotoGallery(photos: List<String>, media: File, editing: Boolean,
    remove: (String) -> Unit, move: (Int, Int) -> Unit) {
    if (photos.isEmpty()) return
    var selected by remember { mutableStateOf<Int?>(null) }
    var ratios by remember(photos, media) { mutableStateOf<List<Float>?>(null) }
    LaunchedEffect(photos, media) {
        ratios = withContext(Dispatchers.IO) { photos.map { name ->
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(File(media, name).path, options)
            if (options.outWidth > 0 && options.outHeight > 0) options.outWidth.toFloat() / options.outHeight else 1f
        } }
    }
    // Reserve a stable small surface until dimensions have been read off the main thread.
    val layout = ratios?.let(::photoLayout)
    @Composable fun tile(index: Int, modifier: Modifier) {
        var menu by remember(photos[index]) { mutableStateOf(false) }
        Box(modifier.clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
            AsyncImage(File(media, photos[index]), "图片 ${index + 1}，查看大图", Modifier.fillMaxSize().clickable { selected = index }, contentScale = ContentScale.Crop)
            Box(Modifier.align(Alignment.TopEnd).padding(3.dp)) {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) {
                    Box(Modifier.size(34.dp).background(Color.Black.copy(alpha = .45f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.MoreVert, "图片 ${index + 1} 的选项", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("查看原比例大图") }, onClick = { menu = false; selected = index })
                    if (editing) {
                        if (index > 0) DropdownMenuItem(text = { Text("向前移动") }, onClick = { menu = false; move(index, index - 1) })
                        if (index < photos.lastIndex) DropdownMenuItem(text = { Text("向后移动") }, onClick = { menu = false; move(index, index + 1) })
                        DropdownMenuItem(text = { Text("移除图片") }, onClick = { menu = false; remove(photos[index]) })
                    }
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp))) {
        when (layout) {
            is PhotoLayout.PortraitHero -> Row(Modifier.fillMaxWidth().aspectRatio(1.26f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                tile(0, Modifier.weight(.6f).fillMaxHeight())
                Column(Modifier.weight(.4f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    tile(1, Modifier.weight(1f).fillMaxWidth())
                    tile(2, Modifier.weight(1f).fillMaxWidth())
                }
            }
            is PhotoLayout.Rows -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                layout.rows.forEach { row ->
                    Row(Modifier.fillMaxWidth().aspectRatio(row.ratios.sum()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        row.indices.forEachIndexed { j, index -> tile(index, Modifier.weight(row.ratios[j]).fillMaxHeight()) }
                    }
                }
            }
            null -> Box(Modifier.fillMaxWidth().height(180.dp).background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
    }
    selected?.let { start ->
        PhotoViewer(photos, media, start.coerceIn(0, photos.lastIndex)) { selected = null }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable private fun PhotoViewer(photos: List<String>, media: File, initial: Int, close: () -> Unit) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val pager = rememberPagerState(initialPage = initial, pageCount = { photos.size })
        var zoomed by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoomed) { index ->
                var scale by remember { mutableFloatStateOf(1f) }
                var offset by remember { mutableStateOf(Offset.Zero) }
                LaunchedEffect(pager.currentPage) { scale = 1f; offset = Offset.Zero; zoomed = false }
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val width = constraints.maxWidth.toFloat()
                    val height = constraints.maxHeight.toFloat()
                    val transform = rememberTransformableState { zoom, pan, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        zoomed = scale > 1.01f
                        val next = if (zoomed) offset + pan else Offset.Zero
                        offset = Offset(next.x.coerceIn(-width * (scale - 1) / 2, width * (scale - 1) / 2),
                            next.y.coerceIn(-height * (scale - 1) / 2, height * (scale - 1) / 2))
                    }
                    AsyncImage(File(media, photos[index]), "图片 ${index + 1}，共 ${photos.size} 张", Modifier.fillMaxSize()
                        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; offset = Offset.Zero; zoomed = scale > 1f }) }
                        .transformable(state = transform, canPan = { scale > 1.01f })
                        .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
                        contentScale = ContentScale.Fit)
                }
            }
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = close) { Icon(Icons.Outlined.Close, "关闭大图", tint = Color.White) }
                Text("${pager.currentPage + 1} / ${photos.size}", color = Color.White, modifier = Modifier.weight(1f), fontSize = 15.sp)
            }
        }
    }
}
