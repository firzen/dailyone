package com.firzen.dailyone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DailyOneTheme { DailyOneApp() } }
    }
}
private val Blue = Color(0xFF168CCC)
@Composable internal fun DailyOneTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF65BCEF), background = Color(0xFF191B1E), surface = Color(0xFF191B1E))
    else lightColorScheme(primary = Blue, background = Color.White, surface = Color.White,
        onSurface = Color(0xFF24282C), onSurfaceVariant = Color(0xFF858B92), outlineVariant = Color(0xFFEDF0F2))
    MaterialTheme(colorScheme = colors, content = content)
}
private fun date(time: Long, pattern: String): String = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern(pattern, Locale.SIMPLIFIED_CHINESE))

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun DailyOneApp(vm: JournalViewModel = viewModel()) {
    val all by vm.entries.collectAsStateWithLifecycle()
    val entries = all.filterNot { it.deleted }
    val snackbar = remember { SnackbarHostState() }
    var settingsOpen by remember { mutableStateOf(false) }
    val draft = vm.draft
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = MaterialTheme.colorScheme.background) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            if (draft == null) JournalList(entries, vm.loading, vm.busy, vm.config.url.isNotBlank(),
                vm.store.mediaDir, { vm.open(it) }, { vm.open() }, { settingsOpen = true }, vm::sync)
            else Editor(draft, vm.store.mediaDir, vm.saved, vm.importing, vm::change, vm::back, vm::addPhoto, vm::delete)
            if (settingsOpen) {
                ModalBottomSheet(onDismissRequest = { settingsOpen = false }) {
                    CloudForm(vm.config, vm.busy, vm.settingsError, { vm.configure(it) { settingsOpen = false } })
                }
            }
        }
    }
}

@Composable private fun JournalList(entries: List<Entry>, loading: Boolean, busy: Boolean, configured: Boolean,
    media: File, open: (Entry) -> Unit, create: () -> Unit, settings: () -> Unit, sync: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 23.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.MenuBook, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Text("我的日记", fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 10.dp).weight(1f))
            IconButton(onClick = settings, enabled = !busy && !loading) { Icon(Icons.Outlined.Tune, "云存储设置", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        HorizontalDivider(thickness = .5.dp)
        Row(Modifier.fillMaxWidth().padding(horizontal = 23.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${entries.size} 篇日记", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = if (configured) sync else settings, enabled = !busy && !loading, contentPadding = PaddingValues(0.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.5.dp)
                else Icon(Icons.Outlined.Cloud, null, Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (busy) "正在同步" else if (configured) "立即同步" else "仅保存在本机", fontSize = 12.sp)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
            else if (entries.isEmpty()) Column(Modifier.align(Alignment.Center).padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.AutoStories, null, Modifier.size(38.dp), tint = MaterialTheme.colorScheme.outline)
                Text("从今天开始记录", Modifier.padding(top = 18.dp), fontSize = 18.sp)
                Text("留下一点文字，以后就能想起今天。", Modifier.padding(top = 9.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                val groups = entries.groupBy { date(it.createdAt, "yyyy-MM") }
                groups.forEach { (_, group) ->
                    item(key = "month-${group.first().id}") {
                        Text(date(group.first().createdAt, "M月  yyyy"), Modifier.fillMaxWidth().padding(horizontal = 23.dp, vertical = 17.dp), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        HorizontalDivider(thickness = .5.dp)
                    }
                    items(group, key = { it.id }) { entry ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { open(entry) }.padding(horizontal = 23.dp, vertical = 22.dp)) {
                            Column(Modifier.width(40.dp)) {
                                Text(date(entry.createdAt, "dd"), fontSize = 30.sp, lineHeight = 32.sp)
                                Text(date(entry.createdAt, "EEE"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                            }
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(date(entry.createdAt, "HH:mm"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (entry.title.isNotBlank()) Text(entry.title, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 9.dp))
                                if (entry.body.isNotBlank()) Text(entry.body, fontSize = 13.sp, lineHeight = 23.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                                entry.photos.firstOrNull()?.let { name ->
                                    AsyncImage(File(media, name), "日记图片", Modifier.padding(top = 13.dp).fillMaxWidth().height(156.dp).clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.Crop)
                                }
                            }
                        }
                        HorizontalDivider(thickness = .5.dp)
                    }
                }
            }
            FloatingActionButton(onClick = { if (!busy && !loading) create() }, modifier = Modifier.align(Alignment.BottomEnd).padding(23.dp),
                shape = RoundedCornerShape(50), containerColor = MaterialTheme.colorScheme.primary, contentColor = Color.White) {
                Icon(Icons.Outlined.Add, "写日记", Modifier.size(29.dp))
            }
        }
    }
}

@Composable internal fun Editor(entry: Entry, media: File, saved: Boolean, importing: Boolean,
    change: (Entry) -> Unit, back: () -> Unit, addPhoto: (android.net.Uri) -> Unit, delete: () -> Unit) {
    BackHandler(onBack = back)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(addPhoto) }
    var confirmDelete by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back, enabled = saved && !importing) { Icon(Icons.Outlined.ArrowBack, "保存并返回") }
            Text(date(entry.createdAt, "yyyy年M月d日"), Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            TextButton(onClick = back, enabled = saved && !importing) { Text("完成") }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 26.dp)) {
            Row(Modifier.padding(top = 12.dp, bottom = 25.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Schedule, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(date(entry.createdAt, "HH:mm · EEEE"), Modifier.padding(start = 7.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BasicTextField(entry.title, { change(entry.copy(title = it)) }, Modifier.fillMaxWidth(),
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 24.sp, fontWeight = FontWeight.Medium),
                decorationBox = { field -> Box { if (entry.title.isEmpty()) Text("标题", color = MaterialTheme.colorScheme.outline, fontSize = 24.sp); field() } })
            Spacer(Modifier.height(18.dp))
            BasicTextField(entry.body, { change(entry.copy(body = it)) }, Modifier.fillMaxWidth().heightIn(min = 180.dp),
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, lineHeight = 30.sp),
                decorationBox = { field -> Box { if (entry.body.isEmpty()) Text("记下这一刻……", color = MaterialTheme.colorScheme.outline, fontSize = 16.sp); field() } })
            entry.photos.forEach { name ->
                Box(Modifier.padding(top = 16.dp)) {
                    AsyncImage(File(media, name), "日记图片", Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.FillWidth)
                    IconButton(onClick = { change(entry.copy(photos = entry.photos - name)) }, Modifier.align(Alignment.TopEnd).padding(4.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = .9f), RoundedCornerShape(50))) {
                        Icon(Icons.Outlined.Close, "移除图片")
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
        HorizontalDivider(thickness = .5.dp)
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { picker.launch("image/*") }, enabled = !importing) { Icon(Icons.Outlined.Image, "插入图片", tint = MaterialTheme.colorScheme.primary) }
            IconButton(onClick = { confirmDelete = true }, enabled = saved && !importing) { Icon(Icons.Outlined.DeleteOutline, "删除日记", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.weight(1f))
            Text(if (importing) "正在添加图片…" else if (saved) "已保存到本机" else "正在保存…", Modifier.padding(end = 12.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("删除这篇日记？") },
        text = { Text("同步后，其他设备也会删除这篇日记。") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; delete() }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } })
}

@Composable private fun CloudForm(config: CloudConfig, busy: Boolean, error: String?, save: (CloudConfig) -> Unit) {
    var url by remember(config) { mutableStateOf(config.url) }
    var username by remember(config) { mutableStateOf(config.username) }
    var password by remember(config) { mutableStateOf(config.password) }
    Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("云存储", fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp)).padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Cloud, null, tint = MaterialTheme.colorScheme.primary)
            Text("WebDAV", Modifier.padding(start = 12.dp).weight(1f), fontSize = 16.sp)
            Text("自定义存储", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
        }
        OutlinedTextField(url, { url = it }, label = { Text("服务器地址（HTTPS）") }, placeholder = { Text("https://dav.example.com") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(username, { username = it }, label = { Text("用户名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("密码 / 应用专用密码") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        Button(onClick = { save(CloudConfig(url.trim(), username, password)) }, enabled = !busy && url.isNotBlank(), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(15.dp)) { Text("保存配置") }
        Text("保存后，返回日记列表点“立即同步”。日记始终先保存到本机；云端数据保存在 DailyOne 目录。", fontSize = 12.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
