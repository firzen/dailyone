package com.firzen.dailyone

import android.os.Bundle
import android.os.Build
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import android.app.Activity
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
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
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    var settingsOpen by remember { mutableStateOf(false) }
    val draft = vm.draft?.let { current -> current.copy(photoLocations = all.firstOrNull { it.id == current.id }?.photoLocations.orEmpty() + current.photoLocations) }
    val context = LocalContext.current
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var batchDialog by remember { mutableStateOf<Boolean?>(null) }
    BackHandler(enabled = selectedIds.isNotEmpty()) { selectedIds = emptySet() }
    val toggleSelection: (Entry) -> Unit = { entry -> selectedIds = if (entry.id in selectedIds) selectedIds - entry.id else selectedIds + entry.id }
    fun keep(entry: Entry) {
        if (entry.scanDay == "unknown") { vm.open(entry); return }
        vm.review(entry.id, true)
    }
    val backfillPermissions = (if (Build.VERSION.SDK_INT >= 34) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        else if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)) +
        (if (Build.VERSION.SDK_INT >= 29) arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION) else emptyArray())
    val backfillLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.startGeoBackfill() }
    val backfill = {
        if (backfillPermissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) vm.startGeoBackfill()
        else backfillLauncher.launch(backfillPermissions)
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) vm.recordCurrentLocation() else vm.locationPermissionDenied()
    }
    val fetchCurrentPlace = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) vm.recordCurrentLocation()
        else locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    val recordLocation = { vm.showLocationPicker(); fetchCurrentPlace() }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.any { (permission, granted) -> granted && permission != Manifest.permission.ACCESS_MEDIA_LOCATION }) vm.startScan() else vm.photoPermissionDenied()
    }
    val startScan = {
        val permissions = if (Build.VERSION.SDK_INT >= 34) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            else if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        val wanted = permissions + if (Build.VERSION.SDK_INT >= 29) arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION) else emptyArray()
        if (ContextCompat.checkSelfPermission(context, permissions.first()) == PackageManager.PERMISSION_GRANTED && (Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED)) vm.startScan()
        else permissionLauncher.launch(wanted)
    }
    val view = LocalView.current
    val dark = isSystemInDarkTheme()
    DisposableEffect(view, vm.scanProgress.running, vm.geoProgress.running, vm.importing) {
        val previous = view.keepScreenOn
        view.keepScreenOn = previous || vm.scanProgress.running || vm.geoProgress.running || vm.importing
        onDispose { view.keepScreenOn = previous }
    }
    SideEffect {
        WindowCompat.getInsetsController((view.context as Activity).window, view).isAppearanceLightStatusBars = draft != null && !dark
    }
    LaunchedEffect(vm) { vm.messages.collect { notice ->
        if (snackbar.showSnackbar(notice.message, actionLabel = notice.undoId?.let { "撤销" }) == SnackbarResult.ActionPerformed) notice.undoId?.let(vm::undo)
    } }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = if (draft == null) { if (dark) Color(0xFF252B30) else Color(0xFF333C41) } else MaterialTheme.colorScheme.background) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            if (vm.geoProgress.running) Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("原图恢复 ${vm.geoProgress.checked}/${vm.geoProgress.total} · 已替换 ${vm.geoProgress.replaced} 张", Modifier.weight(1f), fontSize = 12.sp)
                TextButton(onClick = vm::stopGeoBackfill) { Text("停止") }
            }
            Box(Modifier.weight(1f)) {
            if (draft == null) JournalList(listState, entries, vm.loading, vm.busy, vm.config.url.isNotBlank(),
                vm.store.mediaDir, { vm.open(it) }, { vm.open() }, { settingsOpen = true }, vm::sync, vm.scanProgress, startScan, vm::stopScan,
                { entry, retain -> if (retain) keep(entry) else vm.review(entry.id, false) }, selectedIds, toggleSelection,
                { selectedIds = emptySet() }, { batchDialog = false }, { batchDialog = true })
            else Editor(draft, vm.store.mediaDir, vm.saved, vm.importing, vm::change, vm::back, vm::addPhotos, vm::delete, vm::removePhoto, { keep(it) }, recordLocation, vm.locating)
            if (vm.locationPickerOpen && draft != null) LocationPicker(vm.currentPlace, draft.location, vm.placeResults,
                vm.locating, vm.searchingPlaces, vm.placeError, vm::searchPlaces, fetchCurrentPlace, vm::selectPlace, vm::closeLocationPicker)
            batchDialog?.let { merge ->
                val chosen = entries.filter { it.id in selectedIds }
                val preview = if (merge && chosen.size > 1) journalBatch(chosen, true).first().second else null
                AlertDialog(onDismissRequest = { batchDialog = null }, title = { Text(if (merge) "合并 ${chosen.size} 篇日记？" else "删除 ${chosen.size} 篇日记？") },
                    text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        if (preview != null) {
                            Text("合并日期：" + if (preview.scanDay == "unknown") "待确认" else date(preview.createdAt, "yyyy年M月d日 HH:mm"))
                            Text("图片：${preview.photos.size} 张 · " + if (preview.candidate) "仍为候选" else "正式日记")
                            if (preview.title.isNotBlank()) Text(preview.title, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                            Text(preview.body.take(1000) + if (preview.body.length > 1000) "\n…其余文字也会完整保留" else "", modifier = Modifier.padding(top = 8.dp))
                            Text("其余原记录将标记删除，操作后可撤销。", fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                        } else Text("所选记录将标记删除，手机相册原图保留，操作后可撤销。")
                    } },
                    confirmButton = { TextButton(onClick = { vm.batch(selectedIds, merge) { selectedIds = emptySet(); batchDialog = null } }, enabled = !vm.busy && chosen.isNotEmpty()) { Text(if (merge) "合并" else "删除") } },
                    dismissButton = { TextButton(onClick = { batchDialog = null }) { Text("取消") } })
            }
            vm.photoTimePrompt?.let { prompt ->
                var rememberChoice by remember(prompt.entryId, prompt.timestamp) { mutableStateOf(false) }
                AlertDialog(
                    onDismissRequest = { vm.choosePhotoTime(false, false) },
                    title = { Text("是否使用媒体时间？", fontSize = 25.sp) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            Text(date(prompt.timestamp, "yyyy年M月d日 OOOO HH:mm:ss"), fontSize = 15.sp)
                            Text("使用本次所选图片中第一张有拍摄时间的图片。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.fillMaxWidth().toggleable(rememberChoice, role = Role.Checkbox, onValueChange = { rememberChoice = it }).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(rememberChoice, onCheckedChange = null)
                                Text("记住我的选择", Modifier.padding(start = 12.dp), fontSize = 15.sp)
                            }
                            Text("稍后可在设置中更改此项。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    confirmButton = { FilledTonalButton(onClick = { vm.choosePhotoTime(true, rememberChoice) }) { Text("是") } },
                    dismissButton = { FilledTonalButton(onClick = { vm.choosePhotoTime(false, rememberChoice) }) { Text("不") } }
                )
            }
            if (settingsOpen) {
                ModalBottomSheet(onDismissRequest = { settingsOpen = false }) {
                    CloudForm(vm.config, vm.busy, vm.settingsError, vm.placeEndpoint, vm::updatePlaceEndpoint, vm.photoTimePreference, vm::updatePhotoTimePreference, { settingsOpen = false; startScan() }, { settingsOpen = false; backfill() }, { vm.configure(it) { settingsOpen = false } })
                }
            }
        }
    }
    }
}

@Composable private fun JournalList(listState: LazyListState, entries: List<Entry>, loading: Boolean, busy: Boolean, configured: Boolean,
    media: File, open: (Entry) -> Unit, create: () -> Unit, settings: () -> Unit, sync: () -> Unit,
    scan: ScanProgress, startScan: () -> Unit, stopScan: () -> Unit, review: (Entry, Boolean) -> Unit, selection: Set<String>, toggle: (Entry) -> Unit,
    clearSelection: () -> Unit, deleteSelected: () -> Unit, mergeSelected: () -> Unit) {
    val dark = isSystemInDarkTheme()
    val chrome = if (dark) Color(0xFF252B30) else Color(0xFF333C41)
    Column(Modifier.fillMaxSize().background(chrome)) {
        Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selection.isNotEmpty()) IconButton(onClick = clearSelection) { Icon(Icons.Outlined.Close, "退出多选", tint = Color.White) }
            else Icon(Icons.Outlined.MenuBook, null, tint = Color.White, modifier = Modifier.size(25.dp))
            Text(if (selection.isEmpty()) "所有条目" else "已选 ${selection.size} 篇", fontSize = 25.sp, color = Color.White, modifier = Modifier.padding(start = 19.dp).weight(1f))
            if (selection.isNotEmpty()) {
                IconButton(onClick = mergeSelected, enabled = !busy && selection.size > 1) { Icon(Icons.Outlined.MergeType, "合并所选日记", tint = if (selection.size > 1) Color.White else Color.Gray) }
                IconButton(onClick = deleteSelected, enabled = !busy) { Icon(Icons.Outlined.DeleteOutline, "删除所选日记", tint = Color.White) }
            } else {
            IconButton(onClick = if (configured) sync else settings, enabled = !busy && !loading) {
                if (busy) CircularProgressIndicator(Modifier.size(21.dp), color = Color.White, strokeWidth = 2.dp)
                else Icon(Icons.Outlined.CloudSync, if (configured) "立即同步" else "配置云存储", tint = Color.White)
            }
            IconButton(onClick = settings, enabled = !busy && !loading) { Icon(Icons.Outlined.MoreVert, "设置", tint = Color.White) }
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(MaterialTheme.colorScheme.background)) {
            Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.MenuBook, null, Modifier.size(23.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.padding(start = 30.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
                    Spacer(Modifier.height(10.dp))
                    Text("列表", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                    Box(Modifier.width(34.dp).height(3.dp).clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)).background(MaterialTheme.colorScheme.primary))
                }
                Spacer(Modifier.weight(1f))
                Text("${entries.size} 篇", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (scan.running) "${if (scan.stopping) "停止中" else "扫描中"} ${scan.processed}/${scan.total} · 人物 ${scan.matched}" else "人物照片生成日记", fontSize = 13.sp)
                    Text("待确认 ${entries.count { it.candidate }} 篇 · 仅扫描已授权照片", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = if (scan.running) stopScan else startScan, enabled = !loading && !busy && !scan.stopping) { Text(if (scan.running) "停止扫描" else "扫描照片") }
            }
            Box(Modifier.fillMaxSize()) {
                if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
                else if (entries.isEmpty()) Column(Modifier.align(Alignment.Center).padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.AutoStories, null, Modifier.size(38.dp), tint = MaterialTheme.colorScheme.outline)
                    Text("从今天开始记录", Modifier.padding(top = 18.dp), fontSize = 18.sp)
                    Text("留下一点文字，以后就能想起今天。", Modifier.padding(top = 9.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = 9.dp, end = 9.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    entries.groupBy { if (it.scanDay == "unknown") "unknown" else date(it.createdAt, "yyyy-MM") }.forEach { (month, group) ->
                        item(key = "month-$month") {
                            Text(if (month == "unknown") "日期待确认" else date(group.first().createdAt, "MMMM yyyy"), Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 22.dp), fontSize = 25.sp)
                        }
                        items(group, key = { it.id }) { entry ->
                            Box {
                                val card: @Composable () -> Unit = {
                                    JournalCard(entry, media, !busy,
                                        { if (selection.isEmpty()) open(entry) else toggle(entry) }, { toggle(entry) })
                                }
                                if (entry.candidate && selection.isEmpty()) CandidateCard(entry, !busy, { review(entry, it) }, card) else card()
                                if (selection.isNotEmpty()) {
                                    Box(Modifier.matchParentSize().clip(RoundedCornerShape(19.dp)).background(if (entry.id in selection) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else Color.Transparent))
                                    Checkbox(entry.id in selection, onCheckedChange = { toggle(entry) }, enabled = !busy, modifier = Modifier.align(Alignment.TopEnd))
                                }
                            }
                        }
                    }
                }
                FloatingActionButton(onClick = { if (!busy && !loading) create() }, modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp),
                    shape = RoundedCornerShape(18.dp), containerColor = chrome, contentColor = Color.White) {
                    Icon(Icons.Outlined.Add, "写日记", Modifier.size(29.dp))
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable internal fun JournalCard(entry: Entry, media: File, enabled: Boolean, open: () -> Unit, select: () -> Unit) {
    val dark = isSystemInDarkTheme()
    val card = if (dark) Color(0xFF282F35) else Color(0xFFF0F3F7)
    val capsule = if (dark) Color(0xFF353E45) else Color(0xFFE3E7EC)
    val hasText = entry.title.isNotBlank() || entry.body.isNotBlank()
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(19.dp)).background(card).combinedClickable(enabled = enabled, onClick = open, onLongClick = select).padding(9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(61.dp).height(96.dp).clip(RoundedCornerShape(40.dp)).background(capsule),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(if (entry.scanDay == "unknown") "待确认" else date(entry.createdAt, "EEE"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (entry.scanDay == "unknown") "—" else date(entry.createdAt, "dd"), fontSize = 27.sp, lineHeight = 34.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f).heightIn(min = 96.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.weight(1f, fill = false), verticalAlignment = Alignment.Top) {
                if (hasText) Column(Modifier.weight(1f).padding(end = if (entry.photos.isEmpty()) 0.dp else 8.dp)) {
                    if (entry.title.isNotBlank()) Text(entry.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (entry.body.isNotBlank()) Text(entry.body, Modifier.padding(top = if (entry.title.isBlank()) 0.dp else 4.dp),
                        fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (entry.photos.isNotEmpty()) PhotoThumbnails(entry.photos, media, hasText)
            }
            Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("日记", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                Text(" · " + if (entry.scanDay == "unknown") "日期待确认" else date(entry.createdAt, "HH:mm"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
    }
}

@Composable private fun PhotoThumbnails(photos: List<String>, media: File, compact: Boolean) {
    val limit = if (compact) 4 else 3
    @Composable fun thumb(index: Int, modifier: Modifier) {
        Box(modifier.clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
            AsyncImage(File(media, photos[index]), "日记图片 ${index + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (index == limit - 1 && photos.size > limit) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = .65f)), contentAlignment = Alignment.Center) {
                Text("+${photos.size - limit + 1}", color = Color(0xFF202629), fontSize = 18.sp)
            }
        }
    }
    if (compact) {
        val count = minOf(photos.size, 4)
        Column(Modifier.size(96.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (count == 1) thumb(0, Modifier.fillMaxSize())
            else {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    thumb(0, Modifier.weight(1f).fillMaxHeight())
                    thumb(1, Modifier.weight(1f).fillMaxHeight())
                }
                if (count > 2) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    thumb(2, Modifier.weight(1f).fillMaxHeight())
                    if (count > 3) thumb(3, Modifier.weight(1f).fillMaxHeight()) else Spacer(Modifier.weight(1f))
                }
            }
        }
    } else Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        photos.take(limit).indices.forEach { thumb(it, Modifier.size(68.dp)) }
    }
}

@Composable internal fun Editor(entry: Entry, media: File, saved: Boolean, importing: Boolean,
    change: (Entry) -> Unit, back: () -> Unit, addPhotos: (List<android.net.Uri>) -> Unit, delete: () -> Unit,
    removePhoto: ((String) -> Unit)? = null, keepCandidate: (Entry) -> Unit = {}, recordLocation: () -> Unit = {}, locating: Boolean = false) {
    BackHandler(onBack = back)
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents(), addPhotos)
    val mediaLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { picker.launch("image/*") }
    val selectPhotos = {
        if (Build.VERSION.SDK_INT >= 29 && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED)
            mediaLocation.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
        else picker.launch("image/*")
    }
    var editing by remember(entry.id) { mutableStateOf(entry.isEmpty || entry.scanDay == "unknown") }
    var confirmDate by remember(entry.id) { mutableStateOf(entry.scanDay == "unknown") }
    var confirmDelete by remember { mutableStateOf(false) }
    var entryMenu by remember { mutableStateOf(false) }
    val latestEntry by rememberUpdatedState(entry)
    val cursor = SolidColor(MaterialTheme.colorScheme.primary)
    val detailScroll = rememberScrollState()
    var detailViewport by remember { mutableStateOf<Rect?>(null) }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back, enabled = saved && !importing) { Icon(Icons.Outlined.ArrowBack, "保存并返回") }
            Text(if (entry.scanDay == "unknown") "日期待确认" else date(entry.createdAt, "EEE，yyyy年M月d日"), Modifier.weight(1f).padding(start = 8.dp).clickable(enabled = editing) { confirmDate = true }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 21.sp)
            if (editing) TextButton(onClick = {
                if (entry.candidate) {
                    if (entry.scanDay == "unknown") { confirmDate = true } else { keepCandidate(entry); editing = false }
                } else editing = false
            }, enabled = saved && !importing) { Text(if (entry.candidate) "保存" else "完成") }
            else IconButton(onClick = { editing = true }) { Icon(Icons.Outlined.Edit, "编辑日记") }
            Box {
                IconButton(onClick = { entryMenu = true }, enabled = saved && !importing) { Icon(Icons.Outlined.MoreVert, "日记选项") }
                DropdownMenu(expanded = entryMenu, onDismissRequest = { entryMenu = false }) {
                    DropdownMenuItem(text = { Text("修改日期和时间") }, onClick = { entryMenu = false; editing = true; confirmDate = true })
                    DropdownMenuItem(text = { Text("删除日记") }, onClick = { entryMenu = false; confirmDelete = true })
                }
            }
        }
        if (editing) TextButton(onClick = { confirmDate = true }, modifier = Modifier.padding(start = 10.dp)) {
            Icon(Icons.Outlined.Schedule, null, Modifier.size(17.dp))
            Text(if (entry.scanDay == "unknown") "设置日期和时间" else date(entry.createdAt, "yyyy年M月d日 HH:mm"), Modifier.padding(start = 8.dp))
        }
        Column(Modifier.weight(1f).onGloballyPositioned { detailViewport = it.boundsInRoot() }.verticalScroll(detailScroll).padding(horizontal = 18.dp).padding(top = 18.dp)) {
            if (editing) {
                BasicTextField(entry.title, { change(entry.copy(title = it)) }, Modifier.fillMaxWidth(), cursorBrush = cursor,
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 32.sp, fontWeight = FontWeight.Bold),
                    decorationBox = { field -> Box { if (entry.title.isEmpty()) Text("标题", color = MaterialTheme.colorScheme.outline, fontSize = 32.sp); field() } })
                Spacer(Modifier.height(15.dp))
                BasicTextField(entry.body, { change(entry.copy(body = it)) }, Modifier.fillMaxWidth().heightIn(min = if (entry.photos.isEmpty()) 140.dp else 60.dp), cursorBrush = cursor,
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp, lineHeight = 30.sp),
                    decorationBox = { field -> Box { if (entry.body.isEmpty()) Text("记下这一刻……", color = MaterialTheme.colorScheme.outline, fontSize = 17.sp); field() } })
                Spacer(Modifier.height(16.dp))
            } else {
                if (entry.title.isNotBlank()) Text(entry.title, Modifier.padding(bottom = 19.dp), fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold)
                if (entry.body.isNotBlank()) Text(entry.body, Modifier.padding(bottom = 20.dp), fontSize = 17.sp, lineHeight = 30.sp)
            }
            PhotoGallery(entry.photos, media, editing,
                remove = { name -> if (removePhoto != null) removePhoto(name) else change(entry.copy(photos = entry.photos - name)) },
                move = { from, to -> val reordered = entry.photos.toMutableList(); val photo = reordered.removeAt(from); reordered.add(to, photo); change(entry.copy(photos = reordered)) }, scrollState = detailScroll, dragViewport = detailViewport)
            Spacer(Modifier.height(24.dp))
        }
        val location = entry.location
        OutlinedCard(onClick = recordLocation, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
            shape = RoundedCornerShape(12.dp), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.LocationOn, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(location?.label() ?: "添加位置", fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(location?.address?.takeIf { it.isNotBlank() && it != location.name } ?: "搜索地点、店名，或使用当前位置",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (location?.source == "OpenStreetMap") Text("© OpenStreetMap contributors", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Outlined.ChevronRight, "选择或修改位置", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (editing) {
            HorizontalDivider(thickness = .5.dp)
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = selectPhotos, enabled = !importing) { Icon(Icons.Outlined.AddPhotoAlternate, "添加图片，可多选", tint = MaterialTheme.colorScheme.primary) }
                Text("${entry.photos.size} 张", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text(if (importing) "正在添加图片…" else if (saved) "已保存到本机" else "正在保存…", Modifier.padding(end = 12.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
            Text((if (entry.scanDay == "unknown") "日期待确认" else date(entry.createdAt, "HH:mm")) + " · ", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("日记", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
    if (confirmDate) JournalDateTimeDialog(entry.createdAt, confirm = { timestamp ->
        change(latestEntry.withJournalTime(timestamp)); confirmDate = false
    }, close = { confirmDate = false })
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("删除这篇日记？") },
        text = { Text("同步后，其他设备也会删除这篇日记。") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; delete() }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } })
}

@Composable private fun CloudForm(config: CloudConfig, busy: Boolean, error: String?, placeEndpoint: String, updatePlaceEndpoint: (String) -> String?, photoTimePreference: PhotoTimePreference,
    updatePhotoTimePreference: (PhotoTimePreference) -> Unit, startScan: () -> Unit, backfill: () -> Unit, save: (CloudConfig) -> Unit) {
    var url by remember(config) { mutableStateOf(config.url) }
    var username by remember(config) { mutableStateOf(config.username) }
    var password by remember(config) { mutableStateOf(config.password) }
    Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("设置", fontSize = 22.sp, fontWeight = FontWeight.Medium)
        OutlinedButton(onClick = startScan, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("扫描相册 · 生成人物日记") }
        OutlinedButton(onClick = backfill, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("恢复旧照片原图并补全定位") }
        var photoTimeMenu by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("添加图片时", modifier = Modifier.weight(1f), fontSize = 15.sp)
            Box {
                TextButton(onClick = { photoTimeMenu = true }) { Text(photoTimePreference.label) }
                DropdownMenu(expanded = photoTimeMenu, onDismissRequest = { photoTimeMenu = false }) {
                    PhotoTimePreference.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(option.label) }, onClick = { updatePhotoTimePreference(option); photoTimeMenu = false })
                    }
                }
            }
        }
        HorizontalDivider()
        Text("地点搜索", fontSize = 17.sp, fontWeight = FontWeight.Medium)
        Text("OpenStreetMap 开放地图，无需 Key。可改为其他兼容 Photon 的服务器。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        var searchServer by remember(placeEndpoint) { mutableStateOf(placeEndpoint) }
        var searchServerError by remember { mutableStateOf<String?>(null) }
        OutlinedTextField(searchServer, { searchServer = it; searchServerError = null }, label = { Text("地点搜索服务器（HTTPS）") },
            singleLine = true, isError = searchServerError != null, modifier = Modifier.fillMaxWidth())
        if (searchServerError != null) Text(searchServerError!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        OutlinedButton(onClick = { searchServerError = updatePlaceEndpoint(searchServer) }, enabled = !busy) { Text("保存地点搜索设置") }
        HorizontalDivider()
        Text("云存储", fontSize = 17.sp, fontWeight = FontWeight.Medium)
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
