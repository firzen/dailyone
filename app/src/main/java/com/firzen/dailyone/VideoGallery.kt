package com.firzen.dailyone

import android.media.MediaMetadataRetriever
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable internal fun VideoThumbnail(file: File, modifier: Modifier = Modifier, play: (() -> Unit)? = null) {
    var bitmap by remember(file.path) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(file.path) {
        bitmap = withContext(Dispatchers.IO) { runCatching {
            val retriever = MediaMetadataRetriever()
            try { retriever.setDataSource(file.path)
                if (android.os.Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 320)
                else retriever.frameAtTime
            } finally { retriever.release() }
        }.getOrNull() }
    }
    Box(modifier.background(Color.DarkGray).then(if (play != null) Modifier.clickable(onClick = play) else Modifier), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), "视频缩略图", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Text("▶", color = Color.White, style = MaterialTheme.typography.headlineMedium)
    }
}
@Composable internal fun VideoPlayer(file: File, close: () -> Unit) {
    var player by remember { mutableStateOf<VideoView?>(null) }
    var error by remember(file) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) player?.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); player?.stopPlayback() }
    }
    Dialog(onDismissRequest = close) {
        Surface { Column {
            if (error) Text("设备无法解码此视频，原文件已保留。", Modifier.padding(20.dp))
            else AndroidView(factory = { context -> VideoView(context).apply {
                player = this
                setMediaController(MediaController(context).also { it.setAnchorView(this) })
                setOnErrorListener { _, _, _ -> error = true; true }
                setVideoPath(file.path); setOnPreparedListener { start() }
            } }, modifier = Modifier.fillMaxWidth().height(340.dp))
            TextButton(onClick = close) { Text("关闭视频") }
        } }
    }
}
