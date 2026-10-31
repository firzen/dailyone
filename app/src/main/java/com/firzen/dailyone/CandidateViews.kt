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
            SwipeToDismissBoxValue.StartToEnd -> latestReview(false)
            SwipeToDismissBoxValue.EndToStart -> latestReview(true)
            SwipeToDismissBoxValue.Settled -> Unit
        }
        false // The database decides when to remove the overlay; errors leave the card intact.
    }, positionalThreshold = { it * .35f })
    SwipeToDismissBox(state = state, enableDismissFromStartToEnd = enabled, enableDismissFromEndToStart = enabled,
        backgroundContent = {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(19.dp)).background(if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) Color(0xFF397B60) else Color(0xFFB84446)).padding(18.dp),
                contentAlignment = if (state.dismissDirection == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd) {
                Text(if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) "保留为日记" else "删除候选", color = Color.White)
            }
        }) {
        Box {
            content()
            Box(Modifier.matchParentSize().clip(RoundedCornerShape(19.dp)).background(Color(0xFFB6323D).copy(alpha = .17f)))
            Row(Modifier.align(Alignment.BottomEnd).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("← 左滑保留 · 右滑删除 →", color = Color(0xFF8B2431), fontSize = 11.sp,
                    modifier = Modifier.background(Color(0xFFFFE6E8).copy(alpha = .94f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 3.dp))
            }
        }
    }
}
