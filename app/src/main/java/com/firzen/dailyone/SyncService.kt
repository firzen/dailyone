package com.firzen.dailyone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/** UI and persistent workers share the same store and short database lock. */
object SyncRuntime {
    private var journal: JournalStore? = null
    @Synchronized fun store(context: Context): JournalStore = journal ?: JournalStore(context.applicationContext).also { journal = it }
    internal var davFactory: (CloudConfig) -> DavClient = { DavClient(it) }
    internal val controls = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val sessionLock = Mutex()
    val progress = MutableStateFlow(SyncProgress())
    val notices = JournalNotices()
    private const val workName = "dailyone_sync"
    private const val prefsName = "sync_task"
    private fun prefs(context: Context) = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    internal fun token(context: Context): String? = prefs(context).getString("token", null)
    internal fun config(context: Context) = CloudSettings(context, prefsName).load()

    internal fun isPaused(context: Context) = prefs(context).getBoolean("paused", false)
    @Synchronized fun start(context: Context, config: CloudConfig, initiallyPaused: Boolean = false) {
        if (token(context) == null) {
            CloudSettings(context, prefsName).save(config)
            check(prefs(context).edit().putString("token", java.util.UUID.randomUUID().toString()).putBoolean("paused", initiallyPaused).commit())
            progress.value = SyncProgress(waiting = true, stage = "等待网络或自动重试")
        }
        resume(context)
    }
    @Synchronized fun resume(context: Context) {
        val token = token(context) ?: return
        if (isPaused(context)) {
            progress.value = SyncProgress(percent = prefs(context).getInt("percent", 0), stage = if (progress.value.controlling) "正在暂停同步" else "同步已暂停", paused = true, controlling = progress.value.controlling)
            return
        }
        if (!progress.value.running) progress.value = progress.value.copy(waiting = true, paused = false, controlling = false, stage = "等待网络或自动重试")
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf("token" to token)).addTag(workName)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
        WorkManager.getInstance(context).enqueueUniqueWork("${workName}_$token", ExistingWorkPolicy.KEEP, request)
    }
    suspend fun pause(context: Context) = withContext(Dispatchers.IO) {
        val expected = synchronized(this@SyncRuntime) {
            val current = token(context) ?: return@withContext
            check(prefs(context).edit().putBoolean("paused", true).putInt("percent", progress.value.percent).commit())
            progress.value = progress.value.copy(running = false, waiting = false, paused = true, controlling = true, stage = "正在暂停同步")
            current
        }
        WorkManager.getInstance(context).cancelUniqueWork("${workName}_$expected").result.get()
        sessionLock.withLock {
            if (token(context) == expected) progress.value = SyncProgress(percent = prefs(context).getInt("percent", 0), paused = true, stage = "同步已暂停")
        }
    }
    suspend fun continueSync(context: Context) = withContext(Dispatchers.IO) {
        synchronized(this@SyncRuntime) {
            if (token(context) != null) {
                check(prefs(context).edit().putBoolean("paused", false).commit())
                resume(context)
            }
        }
    }
    @Synchronized internal fun complete(context: Context, expected: String) {
        if (token(context) == expected) check(prefs(context).edit().clear().commit())
    }
    suspend fun cancel(context: Context) {
        withContext(Dispatchers.IO) {
            synchronized(this@SyncRuntime) { check(prefs(context).edit().clear().commit()) }
            WorkManager.getInstance(context).cancelAllWorkByTag(workName).result.get()
            // Wait for an in-flight request to leave the shared store before switching accounts.
            sessionLock.withLock { progress.value = SyncProgress() }
        }
    }
}

class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private val notificationId = 1201
    private fun foreground(state: SyncProgress): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("journal_sync", "日记同步", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(applicationContext, "journal_sync").setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("日记同步 ${state.percent}%").setContentText(state.stage).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setProgress(100, state.percent, false).build()
        return ForegroundInfo(notificationId, notification, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
    }
    override suspend fun getForegroundInfo(): ForegroundInfo = foreground(SyncProgress(true, stage = "准备同步"))
    override suspend fun doWork(): Result = SyncRuntime.sessionLock.withLock {
        val token = inputData.getString("token") ?: return@withLock Result.success()
        if (SyncRuntime.token(applicationContext) != token || SyncRuntime.isPaused(applicationContext)) return@withLock Result.success()
        try {
            setForeground(getForegroundInfo())
            SyncRuntime.progress.value = SyncProgress(true, stage = "准备同步")
            val config = withContext(Dispatchers.IO) { SyncRuntime.config(applicationContext) }
            check(config.url.isNotBlank()) { "请先配置 WebDAV 云存储" }
            val store = SyncRuntime.store(applicationContext)
            store.load()
            var conflicts = 0
            var round = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                round++
                var shown = -1
                conflicts += SyncEngine(store, SyncRuntime.davFactory).sync(config) { state ->
                    if (SyncRuntime.token(applicationContext) != token || SyncRuntime.isPaused(applicationContext)) throw CancellationException("同步已暂停或停止")
                    val stage = if (state.percent == 100) "检查新增修改" else state.stage
                    val displayed = state.copy(percent = state.percent.coerceAtMost(99), stage = if (round > 1) "续传新增修改 · $stage" else stage)
                    SyncRuntime.progress.value = displayed
                    if (displayed.percent != shown) {
                        shown = displayed.percent
                        applicationContext.getSystemService(NotificationManager::class.java).notify(notificationId, foreground(displayed).notification)
                    }
                }
                val complete = store.lock.withLock {
                    currentCoroutineContext().ensureActive()
                    if (SyncRuntime.token(applicationContext) != token || SyncRuntime.isPaused(applicationContext)) throw CancellationException("同步已暂停或停止")
                    val pending = store.entries.value.any { it.dirty && it.syncable } || store.notebooks.value.any { it.dirty }
                    if (pending) false else {
                        SyncRuntime.complete(applicationContext, token)
                        val message = if (conflicts == 0) {
                            "同步完成"
                        } else {
                            "同步完成，保留了 $conflicts 篇冲突副本"
                        }
                        SyncRuntime.progress.value = SyncProgress(percent = 100, message = message)
                        SyncRuntime.notices.trySend(message)
                        true
                    }
                }
                if (complete) break
                delay(500)
            }
            Result.success()
        } catch (e: CancellationException) {
            waiting(token, "等待网络恢复后继续同步")
            throw e
        } catch (e: Exception) {
            waiting(token, "同步暂未完成，将自动重试：${e.message ?: "连接失败"}")
            Result.retry()
        }
    }
    private fun waiting(token: String, message: String) {
        if (SyncRuntime.token(applicationContext) == token && !SyncRuntime.isPaused(applicationContext)) SyncRuntime.progress.value = SyncRuntime.progress.value.copy(running = false, waiting = true, stage = message, message = message)
    }
}
