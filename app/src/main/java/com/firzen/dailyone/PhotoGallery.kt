package com.firzen.dailyone

import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.material.icons.outlined.Share
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
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

internal fun photoAspect(file: File): Float {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return 1f
    val orientation = runCatching { ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
    val rotated = orientation in listOf(ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_ROTATE_90,
        ExifInterface.ORIENTATION_TRANSVERSE, ExifInterface.ORIENTATION_ROTATE_270)
    return if (rotated) bounds.outHeight.toFloat() / bounds.outWidth else bounds.outWidth.toFloat() / bounds.outHeight
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable internal fun PhotoGallery(photos: List<String>, media: File, editing: Boolean,
    remove: (String) -> Unit, move: (Int, Int) -> Unit,
    scrollState: ScrollState? = null, dragViewport: Rect? = null) {
    if (photos.isEmpty()) return
    val latestPhotos by rememberUpdatedState(photos)
    val latestMove by rememberUpdatedState(move)
    val latestViewport by rememberUpdatedState(dragViewport)
    val tileBounds = remember { mutableMapOf<String, Rect>() }
    var galleryOrigin by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragRect by remember { mutableStateOf<Rect?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dragPoint by remember { mutableStateOf(Offset.Zero) }
    var dropTarget by remember { mutableStateOf<String?>(null) }
    val density = LocalDensity.current
    val edge = with(density) { 56.dp.toPx() }
    val step = with(density) { 10.dp.toPx() }
    fun updateTarget() {
        if (latestViewport != null && latestViewport?.contains(dragPoint) == false) { dropTarget = null; return }
        dropTarget = tileBounds.entries.firstOrNull { it.key != dragging && it.key in latestPhotos && it.value.contains(dragPoint) }?.key
    }
    fun clearDrag() { dragging = null; dragRect = null; dragOffset = Offset.Zero; dropTarget = null }
    LaunchedEffect(dragging, scrollState, dragViewport) {
        if (scrollState != null && dragViewport != null) {
            while (dragging != null) {
                withFrameNanos { }
                val amount = when {
                    dragPoint.y < dragViewport.top + edge -> -step
                    dragPoint.y > dragViewport.bottom - edge -> step
                    else -> 0f
                }
                if (amount != 0f) scrollState.scrollBy(amount)
                updateTarget()
            }
        }
    }
    LaunchedEffect(photos) {
        tileBounds.keys.retainAll(photos.toSet())
        if (dragging != null && dragging !in photos) clearDrag()
    }
    var selected by remember { mutableStateOf<Int?>(null) }
    var ratios by remember(media) { mutableStateOf<Map<String, Float>>(emptyMap()) }
    LaunchedEffect(photos, media) {
        val missing = photos.filterNot { it in ratios }
        if (missing.isNotEmpty()) {
            val measured = withContext(Dispatchers.IO) { missing.associateWith { name ->
                photoAspect(File(media, name))
            } }
            ratios = ratios + measured
        }
    }
    val layout = if (photos.all { it in ratios }) photoLayout(photos.map { ratios.getValue(it) }) else null
    @Composable fun tile(index: Int, modifier: Modifier) {
        val name = photos[index]
        var menu by remember(name) { mutableStateOf(false) }
        var tileRect by remember(name) { mutableStateOf<Rect?>(null) }
        val visible by remember(name) { derivedStateOf {
            val viewport = latestViewport
            viewport == null || tileRect?.overlaps(Rect(viewport.left, viewport.top - edge, viewport.right, viewport.bottom + edge)) == true
        } }
        Box(modifier.onGloballyPositioned { coordinates ->
            val bounds = Rect(coordinates.positionInRoot(), Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
            tileBounds[name] = bounds
            tileRect = bounds
        }.pointerInput(name) {
            detectDragGesturesAfterLongPress(
                onDragStart = { point ->
                    tileBounds[name]?.let { rect ->
                        dragging = name; dragRect = rect; dragOffset = Offset.Zero; dragPoint = rect.topLeft + point
                    }
                },
                onDrag = { change, delta ->
                    change.consume(); dragOffset += delta; dragPoint += delta; updateTarget()
                },
                onDragEnd = {
                    val from = latestPhotos.indexOf(dragging)
                    val to = latestPhotos.indexOf(dropTarget)
                    clearDrag()
                    if (from >= 0 && to >= 0 && from != to) latestMove(from, to)
                },
                onDragCancel = { clearDrag() }
            )
        }.clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
            .then(if (dropTarget == name) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(5.dp)) else Modifier)) {
            if (visible || dragging == name) AsyncImage(File(media, photos[index]), "图片 ${index + 1}，查看大图", Modifier.fillMaxSize().graphicsLayer { alpha = if (dragging == name) .25f else 1f }.clickable { selected = index }, contentScale = ContentScale.Crop)
            Box(Modifier.align(Alignment.TopEnd).padding(3.dp)) {
                IconButton(onClick = { if (editing) remove(photos[index]) else menu = true }, modifier = Modifier.size(48.dp)) {
                    Box(Modifier.size(34.dp).background(Color.Black.copy(alpha = .45f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(if (editing) Icons.Outlined.DeleteOutline else Icons.Outlined.MoreVert,
                            if (editing) "删除图片 ${index + 1}" else "图片 ${index + 1} 的选项", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("查看原比例大图") }, onClick = { menu = false; selected = index })
                    run {
                        if (index > 0) DropdownMenuItem(text = { Text("向前移动") }, onClick = { menu = false; move(index, index - 1) })
                        if (index < photos.lastIndex) DropdownMenuItem(text = { Text("向后移动") }, onClick = { menu = false; move(index, index + 1) })
                        if (editing) DropdownMenuItem(text = { Text("移除图片") }, onClick = { menu = false; remove(photos[index]) })
                    }
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().onGloballyPositioned { galleryOrigin = it.positionInRoot() }.clip(RoundedCornerShape(17.dp))) {
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
        val active = dragging
        val rect = dragRect
        if (active != null && rect != null) {
            val position = rect.topLeft + dragOffset - galleryOrigin
            AsyncImage(File(media, active), "拖动中的图片", Modifier
                .offset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) }
                .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
                .zIndex(2f).graphicsLayer { shadowElevation = 12.dp.toPx(); scaleX = 1.03f; scaleY = 1.03f }
                .clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
        }
    }
    selected?.let { start ->
        PhotoViewer(photos, media, start.coerceIn(0, photos.lastIndex)) { selected = null }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable private fun PhotoViewer(photos: List<String>, media: File, initial: Int, close: () -> Unit) {
    val context = LocalContext.current
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
                IconButton(onClick = {
                    runCatching { context.startActivity(android.content.Intent.createChooser(photoShareIntent(context, File(media, photos[pager.currentPage])), "分享原图")) }
                        .onFailure { android.widget.Toast.makeText(context, it.message ?: "无法分享图片", android.widget.Toast.LENGTH_SHORT).show() }
                }) { Icon(Icons.Outlined.Share, "分享当前图片", tint = Color.White) }
            }
        }
    }
}
