package com.firzen.dailyone

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun Modifier.doubleTapToTop(state: LazyListState, enabled: Boolean): Modifier = if (!enabled) this else pointerInput(state) {
    kotlinx.coroutines.coroutineScope {
        detectTapGestures(onDoubleTap = { launch { state.animateScrollToItem(0) } })
    }
}

/** A fixed-size thumb keeps its touch target stable while variable-height cards scroll. */
@Composable internal fun JournalFastScrollbar(state: LazyListState, labels: List<String>, modifier: Modifier = Modifier) {
    var recent by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var target by remember { mutableIntStateOf(0) }
    val requests = remember(state) { Channel<Int>(Channel.CONFLATED) }
    LaunchedEffect(state, requests) { for (index in requests) state.scrollToItem(index.coerceIn(0, (state.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))) }
    DisposableEffect(requests) { onDispose { requests.close() } }
    LaunchedEffect(state.isScrollInProgress, dragging) {
        if (state.isScrollInProgress || dragging) recent = true
        else { delay(1400); recent = false }
    }
    val scrollable by remember(state) { derivedStateOf { state.canScrollBackward || state.canScrollForward } }
    BoxWithConstraints(modifier.width(190.dp).testTag("journal-fast-scroll-track")) {
        val density = LocalDensity.current
        val thumbHeight = 56.dp
        val travel = with(density) { (maxHeight - thumbHeight).toPx().coerceAtLeast(0f) }
        val fraction by remember(state) { derivedStateOf {
            val info = state.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull { it.index == state.firstVisibleItemIndex }
            val total = info.totalItemsCount
            when {
                !state.canScrollBackward -> 0f
                !state.canScrollForward -> 1f
                total <= 1 -> 0f
                else -> ((state.firstVisibleItemIndex + state.firstVisibleItemScrollOffset.toFloat() / (first?.size ?: 1).coerceAtLeast(1)) / (total - 1)).coerceIn(0f, 1f)
            }
        } }
        val top = if (dragging) dragY.coerceIn(0f, travel) else fraction * travel
        val currentTop by rememberUpdatedState(top)
        val currentLabels by rememberUpdatedState(labels)
        AnimatedVisibility(visible = scrollable && (recent || dragging), enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                if (dragging) Text(currentLabels.getOrNull(target).orEmpty(),
                    Modifier.align(Alignment.TopEnd).offset(x = (-48).dp, y = with(density) { top.toDp() })
                        .background(MaterialTheme.colorScheme.inverseSurface, RoundedCornerShape(8.dp)).padding(10.dp),
                    color = MaterialTheme.colorScheme.inverseOnSurface, fontSize = 12.sp)
                Box(Modifier.align(Alignment.TopEnd).offset(y = with(density) { top.toDp() })
                    .size(width = 44.dp, height = thumbHeight).testTag("journal-fast-scroll")
                    .semantics {
                        contentDescription = "快速滚动日记"
                        progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                        setProgress { value ->
                            val count = state.layoutInfo.totalItemsCount
                            requests.trySend((value.coerceIn(0f, 1f) * (count - 1).coerceAtLeast(0)).toInt())
                            true
                        }
                    }
                    .pointerInput(state, travel) {
                        detectVerticalDragGestures(
                            onDragStart = { dragging = true; dragY = currentTop },
                            onDragEnd = { dragging = false }, onDragCancel = { dragging = false }
                        ) { change, amount ->
                            change.consume()
                            dragY = (dragY + amount).coerceIn(0f, travel)
                            val count = state.layoutInfo.totalItemsCount
                            target = if (travel > 0) ((dragY / travel) * (count - 1).coerceAtLeast(0)).toInt() else 0
                            requests.trySend(target)
                        }
                    }, contentAlignment = Alignment.Center) {
                    Box(Modifier.width(if (dragging) 14.dp else 8.dp).fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = if (dragging) 1f else .7f), RoundedCornerShape(8.dp)))
                }
            }
        }
    }
}
