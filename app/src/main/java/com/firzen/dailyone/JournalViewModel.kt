package com.firzen.dailyone

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class JournalViewModel(app: Application) : AndroidViewModel(app) {
    val store = JournalStore(app)
    private val settings = CloudSettings(app)
    private val preferences = app.getSharedPreferences("journal_preferences", android.content.Context.MODE_PRIVATE)
    var photoTimePreference by mutableStateOf(runCatching {
        PhotoTimePreference.valueOf(preferences.getString("photo_time", "ASK") ?: "ASK")
    }.getOrDefault(PhotoTimePreference.ASK)); private set
    var photoTimePrompt by mutableStateOf<PhotoTimePrompt?>(null); private set
    val entries = store.entries
    var draft by mutableStateOf<Entry?>(null); private set
    var config by mutableStateOf(CloudConfig()); private set
    var settingsError by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set
    var loading by mutableStateOf(true); private set
    var importing by mutableStateOf(false); private set
    var saved by mutableStateOf(true); private set
    private var pending = 0
    private var saveFailed = false
    private val events = JournalNotices()
    val messages = events.flow
    var scanProgress by mutableStateOf(ScanProgress()); private set
    private var scanner: PhotoScanner? = null
    private var geoBackfill: GeoBackfill? = null
    var geoProgress by mutableStateOf(GeoBackfillProgress()); private set
    fun startGeoBackfill() {
        if (geoProgress.running || scanProgress.running || busy || loading) return
        val worker = GeoBackfill(getApplication(), store)
        geoBackfill = worker; geoProgress = GeoBackfillProgress(running = true)
        viewModelScope.launch {
            try {
                val result = worker.run { geoProgress = it.copy(running = true) }
                draft = draft?.let(store::resolvePhotos)
                events.send("原图恢复${if (worker.stop.get()) "已停止" else "完成"}：替换 ${result.replaced} 张，未匹配 ${result.remaining} 张；可用的照片定位已载入")
            } catch (e: Exception) { events.send(e.message ?: "原图恢复中断，已有结果保留") }
            finally { geoBackfill = null; geoProgress = geoProgress.copy(running = false) }
        }
    }
    fun stopGeoBackfill() { geoBackfill?.stop?.set(true) }
    private val places by lazy { PlaceService(getApplication()) }
    var locationPickerOpen by mutableStateOf(false); private set
    var locating by mutableStateOf(false); private set
    var currentPlace by mutableStateOf<GeoPoint?>(null); private set
    var placeResults by mutableStateOf<List<GeoPoint>>(emptyList()); private set
    var searchingPlaces by mutableStateOf(false); private set
    var placeError by mutableStateOf<String?>(null); private set
    private var searchRevision = 0
    private var searchJob: kotlinx.coroutines.Job? = null
    private var currentPlaceJob: kotlinx.coroutines.Job? = null
    private var placeSession = 0
    fun showLocationPicker() {
        if (draft == null) return
        locationPickerOpen = true; placeSession++; currentPlace = null
        placeResults = emptyList(); placeError = null
    }
    fun closeLocationPicker() {
        locationPickerOpen = false; placeSession++
        searchJob?.cancel(); currentPlaceJob?.cancel(); searchingPlaces = false; locating = false
    }
    fun locationPermissionDenied() { placeError = "未允许定位，仍可搜索并选择地点" }
    fun recordCurrentLocation() {
        if (!locationPickerOpen || locating) return
        val session = placeSession
        locating = true; placeError = null
        currentPlaceJob = viewModelScope.launch {
            try {
                val point = currentLocation(getApplication())
                    ?: error("暂未获取到当前位置，仍可搜索地点或稍后重试")
                if (session != placeSession) return@launch
                currentPlace = point
                val named = try { places.reverse(point) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
                if (session == placeSession) currentPlace = named ?: point
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { if (session == placeSession) placeError = e.message ?: "获取当前位置失败" }
            finally { if (session == placeSession) locating = false }
        }
    }
    fun searchPlaces(query: String) {
        val revision = ++searchRevision
        searchJob?.cancel()
        if (query.isBlank()) { searchingPlaces = false; placeResults = emptyList(); placeError = "请输入地点名称或地址"; return }
        val session = placeSession
        searchingPlaces = true; placeError = null; placeResults = emptyList()
        searchJob = viewModelScope.launch {
            try {
                val results = places.search(query)
                if (session == placeSession && revision == searchRevision) {
                    placeResults = results
                    if (results.isEmpty()) placeError = "未找到地点，请加上城市或详细地址重试"
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { if (session == placeSession && revision == searchRevision) placeError = e.message ?: "地点搜索失败，请重试" }
            finally { if (session == placeSession && revision == searchRevision) searchingPlaces = false }
        }
    }
    fun selectPlace(point: GeoPoint) {
        draft?.let { change(it.copy(recordedLocation = point)) }
        closeLocationPicker()
    }
    private fun resolveEntryPlace(entry: Entry) {
        val point = entry.location?.takeIf { it.name.isBlank() && it.address.isBlank() } ?: return
        viewModelScope.launch {
            try {
                val named = places.reverse(point) ?: return@launch
                store.nameLocation(entry.id, point, named)
                draft?.takeIf { it.id == entry.id }?.let { current ->
                    draft = current.copy(
                        recordedLocation = current.recordedLocation?.let { if (it.sameCoordinates(point) && it.name.isBlank()) named else it },
                        photoLocations = current.photoLocations.mapValues { (_, value) -> if (value.sameCoordinates(point) && value.name.isBlank()) named else value })
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { /* Keep the coordinate; the picker offers retry and a custom name. */ }
        }
    }
    private val editingId = AtomicReference<String?>(null)
    private val undoActions = mutableMapOf<String, List<Pair<Entry, Entry>>>()
    fun photoPermissionDenied() { events.trySend("请允许照片访问权限后再开始扫描") }
    fun startScan() {
        if (scanProgress.running || geoProgress.running || loading || busy) return
        val scan = PhotoScanner(getApplication(), store)
        scanner = scan; scanProgress = ScanProgress(running = true)
        viewModelScope.launch {
            try {
                val result = scan.run({ editingId.get() }) { state -> scanProgress = state.copy(running = true, stopping = scan.stop.get()) }
                events.send("扫描${if (scan.stop.get()) "已停止" else "完成"}：识别 ${result.matched} 张人物照片，失败 ${result.failed} 张，候选已保留")
            } catch (e: Exception) { events.send(e.message ?: "扫描中断，已有候选已保留") }
            finally { scanner = null; scanProgress = scanProgress.copy(running = false, stopping = false) }
        }
    }
    fun stopScan() {
        val active = scanner ?: return
        if (!scanProgress.running) return
        active.stop.set(true); scanProgress = scanProgress.copy(stopping = true)
    }
    fun review(id: String, keep: Boolean, selected: List<String>? = null) {
        viewModelScope.launch {
            try {
                val result = store.review(id, keep, selected) ?: return@launch
                if (draft?.id == id) draft = result.second.takeUnless { it.deleted }
                editingId.set(draft?.id)
                val token = UUID.randomUUID().toString()
                if (undoActions.size >= 32) undoActions.remove(undoActions.keys.first()); undoActions[token] = listOf(result)
                events.withUndo(if (keep) "已保留为正式日记" else "候选已删除，相册原图未删除", token)
            } catch (e: Exception) { events.send(e.message ?: "操作失败") }
        }
    }
    fun batch(ids: Set<String>, merge: Boolean, done: () -> Unit) {
        if (busy || loading) return
        busy = true
        viewModelScope.launch {
            try {
                val action = store.batch(ids, merge)
                val token = UUID.randomUUID().toString()
                if (undoActions.size >= 32) undoActions.remove(undoActions.keys.first())
                undoActions[token] = action
                done()
                events.withUndo(if (merge) "已合并 ${action.size} 篇日记" else "已删除 ${action.size} 篇日记，相册原图保留", token)
            } catch (e: Exception) { events.send(e.message ?: "批量操作失败") }
            finally { busy = false }
        }
    }
    fun undo(token: String) {
        val action = undoActions.remove(token) ?: return
        viewModelScope.launch {
            if (store.restoreBatchIfUnchanged(action)) {
                action.firstOrNull { it.first.id == draft?.id }?.let { draft = it.first }
            } else events.send("记录已被修改或同步，无法撤销这次操作")
        }
    }
    fun removePhoto(name: String) {
        val before = draft ?: return
        val after = before.copy(photos = before.photos - name, updatedAt = System.currentTimeMillis(), dirty = !before.candidate)
        change(after)
        viewModelScope.launch {
            store.lock.lock(); store.lock.unlock()
            val actual = store.entries.value.find { it.id == before.id } ?: return@launch
            if (actual.photos == after.photos) {
                val token = UUID.randomUUID().toString(); if (undoActions.size >= 32) undoActions.remove(undoActions.keys.first()); undoActions[token] = listOf(before to actual)
                events.withUndo("已移除图片，相册原图未删除", token)
            }
        }
    }
    override fun onCleared() { scanner?.stop?.set(true); geoBackfill?.stop?.set(true); super.onCleared() }
    init {
        viewModelScope.launch {
            try { store.load(); config = withContext(Dispatchers.IO) { settings.load() } }
            catch (e: Exception) { events.send("读取数据失败：${e.message}") }
            finally { loading = false }
        }
    }
    fun open(entry: Entry = Entry()) { if (!busy && !loading) { draft = entry; editingId.set(entry.id); saved = true; resolveEntryPlace(entry) } }
    fun change(entry: Entry) {
        val next = entry.copy(updatedAt = System.currentTimeMillis(), dirty = !entry.candidate)
        draft = next
        pending++; saved = false
        // Launch on Main: store's mutex is acquired in edit order before dispatching I/O.
        viewModelScope.launch {
            try { store.save(next); saveFailed = false }
            catch (e: Exception) { saveFailed = true; events.send("保存失败，请保留当前页面重试：${e.message}") }
            finally { pending--; saved = pending == 0 && !saveFailed }
        }
    }
    fun back() {
        if (importing) return
        if (!saved) { events.trySend("正在保存，请稍候；如保存失败，请修改内容后重试"); return }
        closeLocationPicker(); draft = null; editingId.set(null)
    }
    fun delete() {
        val entry = draft ?: return
        if (entry.candidate) { review(entry.id, false); return }
        change(entry.copy(deleted = true))
        viewModelScope.launch {
            store.lock.lock()
            store.lock.unlock()
            if (saved) { draft = null; editingId.set(null) }
        }
    }
    fun addPhotos(uris: List<Uri>) {
        val original = draft ?: return
        if (uris.isEmpty() || importing) return
        importing = true
        viewModelScope.launch {
            var firstTime: Long? = null
            try {
                for (uri in uris) {
                    try {
                        val timestamp = store.photoTime(uri)
                        val name = store.importPhoto(uri)
                        if (firstTime == null) firstTime = timestamp
                        val latest = draft
                        if (latest?.id == original.id) {
                            val location = withContext(Dispatchers.IO) { store.knownPhotoLocation(name) }
                            change(latest.copy(photos = latest.photos + name,
                                photoLocations = location?.let { latest.photoLocations + (name to it) } ?: latest.photoLocations))
                        }
                    } catch (e: Exception) { events.send("有一张图片添加失败：${e.message}") }
                }
                val latest = draft
                val timestamp = firstTime
                if (latest?.id == original.id && timestamp != null) {
                    when (photoTimePreference) {
                        PhotoTimePreference.ASK -> photoTimePrompt = PhotoTimePrompt(original.id, timestamp)
                        PhotoTimePreference.USE -> change(latest.withJournalTime(timestamp))
                        PhotoTimePreference.KEEP -> Unit
                    }
                }
            } finally { importing = false }
        }
    }
    fun updatePhotoTimePreference(value: PhotoTimePreference) {
        photoTimePreference = value
        preferences.edit().putString("photo_time", value.name).apply()
    }
    fun choosePhotoTime(use: Boolean, remember: Boolean) {
        val prompt = photoTimePrompt ?: return
        if (remember) updatePhotoTimePreference(if (use) PhotoTimePreference.USE else PhotoTimePreference.KEEP)
        if (use) draft?.takeIf { it.id == prompt.entryId }?.let { change(it.withJournalTime(prompt.timestamp)) }
        photoTimePrompt = null
    }
    fun configure(value: CloudConfig, onSaved: () -> Unit) {
        if (busy) return
        if (scanProgress.running || geoProgress.running) { settingsError = "请先停止扫描或原图恢复"; return }
        busy = true
        settingsError = null
        viewModelScope.launch {
            try {
                DavClient(value) // Validate before persisting credentials.
                if (value.url.trimEnd('/') != config.url.trimEnd('/') || value.username != config.username) {
                    store.resetSyncBaseline()
                }
                withContext(Dispatchers.IO) { settings.save(value) }
                config = value; onSaved(); events.send("云存储配置已保存")
            } catch (e: Exception) { settingsError = e.message ?: "配置保存失败" }
            finally { busy = false }
        }
    }
    fun sync() {
        if (scanProgress.running || geoProgress.running) { events.trySend("请先停止扫描或定位补全，再同步云存储"); return }
        if (busy || loading || pending > 0 || saveFailed) return
        if (config.url.isBlank()) { events.trySend("请先配置 WebDAV 云存储"); return }
        busy = true
        viewModelScope.launch {
            try {
                val count = SyncEngine(store).sync(config)
                events.send(if (count == 0) "同步完成" else "同步完成，保留了 $count 篇冲突副本")
            } catch (e: Exception) { events.send(e.message ?: "同步失败，本地日记已保留") }
            finally { busy = false }
        }
    }
}
