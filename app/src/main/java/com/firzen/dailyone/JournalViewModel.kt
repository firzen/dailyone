package com.firzen.dailyone

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class JournalViewModel(app: Application) : AndroidViewModel(app) {
    val store = JournalStore(app)
    private val settings = CloudSettings(app)
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
    private val events = Channel<String>(Channel.UNLIMITED)
    val messages = events.receiveAsFlow()
    init {
        viewModelScope.launch {
            try { store.load(); config = withContext(Dispatchers.IO) { settings.load() } }
            catch (e: Exception) { events.send("读取数据失败：${e.message}") }
            finally { loading = false }
        }
    }
    fun open(entry: Entry = Entry()) { if (!busy && !loading) { draft = entry; saved = true } }
    fun change(entry: Entry) {
        val next = entry.copy(updatedAt = System.currentTimeMillis(), dirty = true)
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
        draft = null
    }
    fun delete() {
        val entry = draft ?: return
        change(entry.copy(deleted = true))
        viewModelScope.launch {
            store.lock.lock()
            store.lock.unlock()
            if (saved) draft = null
        }
    }
    fun addPhotos(uris: List<Uri>) {
        val original = draft ?: return
        if (uris.isEmpty() || importing) return
        val available = 20 - original.photos.size
        if (available <= 0) { events.trySend("每篇日记最多 20 张图片"); return }
        if (uris.size > available) events.trySend("每篇最多 20 张图片，本次添加前 $available 张")
        importing = true
        viewModelScope.launch {
            try {
                for (uri in uris.take(available)) {
                    try {
                        val name = store.importPhoto(uri)
                        val latest = draft
                        if (latest?.id == original.id) change(latest.copy(photos = latest.photos + name))
                    } catch (e: Exception) { events.send("有一张图片添加失败：${e.message}") }
                }
            } finally { importing = false }
        }
    }
    fun configure(value: CloudConfig, onSaved: () -> Unit) {
        if (busy) return
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
