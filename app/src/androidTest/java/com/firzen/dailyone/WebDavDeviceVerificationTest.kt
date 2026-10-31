package com.firzen.dailyone

import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Opt-in diagnostics; credentials stay in the application's process. */
class WebDavDeviceVerificationTest {
    @Test fun verifySavedConfiguration() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyWebDav") == "true")
        fun report(text: String) {
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("stream", "DAV_DIAG $text\n") })
        }
        report("starting")
        val config = CloudSettings(InstrumentationRegistry.getInstrumentation().targetContext).load()
        check(config.url.startsWith("https://"))
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .callTimeout(30, TimeUnit.SECONDS).build()
        fun probe(label: String, user: String, password: String, path: String) {
            val request = Request.Builder().url(config.url.trim().trimEnd('/') + path)
                .header("Authorization", Credentials.basic(user, password))
                .header("Depth", "0").method("PROPFIND", "".toRequestBody()).build()
            client.newCall(request).execute().use {
                report("$label status=${it.code} server=${it.header("Server")} challenge=${it.header("WWW-Authenticate")?.substringBefore(' ')}")
            }
        }
        report("usernameOuterWhitespace=${config.username != config.username.trim()} passwordOuterWhitespace=${config.password != config.password.trim()}")
        probe("saved-root", config.username, config.password, "/")
        probe("trimmed-user-root", config.username.trim(), config.password, "/")
        probe("trimmed-user-app", config.username.trim(), config.password, "/DailyOne/")
        if (InstrumentationRegistry.getArguments().getString("verifyDavClient") == "true") {
            val dav = DavClient(config)
            dav.ensureCollections()
            report("app-collections=OK")
            report("app-entries-count=${dav.listEntries().size} app-notebooks-count=${dav.listNotebooks().size}")
        }
        if (config.password != config.password.trim()) probe("trimmed-both-root", config.username.trim(), config.password.trim(), "/")
    }
    @Test fun inspectRealEtags() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("inspectEtags") == "true")
        val inst = InstrumentationRegistry.getInstrumentation()
        val config = CloudSettings(inst.targetContext).load()
        val client = OkHttpClient.Builder().followRedirects(false).callTimeout(30, TimeUnit.SECONDS).build()
        val url = config.url.trim().trimEnd('/') + "/DailyOne/photos/" + java.util.UUID.randomUUID() + ".probe"
        fun report(text: String) = inst.sendStatus(0, android.os.Bundle().apply { putString("stream", "ETAG_DIAG $text\n") })
        fun call(method: String, data: String? = null, headers: Map<String, String> = emptyMap()): String? {
            val b = Request.Builder().url(url).header("Authorization", Credentials.basic(config.username, config.password))
            headers.forEach { (k, v) -> b.header(k,v) }
            return client.newCall(b.method(method, data?.toRequestBody()).build()).execute().use {
                val body = it.body?.string()
                report("$method code=${it.code} etag=${it.header("ETag")} " + if (method == "PROPFIND") "hasEtagProperty=${body?.contains("<d:getetag>")}" else "")
                it.header("ETag")
            }
        }
        try {
            call("PUT", "probe1", mapOf("If-None-Match" to "*"))
            call("GET")
            call("HEAD")
            call("PROPFIND", """<d:propfind xmlns:d="DAV:"><d:prop><d:getetag/></d:prop></d:propfind>""", mapOf("Depth" to "0", "Content-Type" to "application/xml"))
            call("PUT", "probe2", mapOf("If-Match" to "\"invalid-probe\""))
            call("PUT", "probe2", mapOf("If-None-Match" to "*"))
            val current = call("GET")
            if (current != null) {
                call("PUT", "probe3", mapOf("If-Match" to current))
                call("PUT", "probe4", mapOf("If-Match" to "\"$current\""))
            }
            val newest = call("GET")
            if (newest != null) call("PUT", "probe5", mapOf("If-Match" to newest))
            val staging = url + ".tmp"
            fun temp(method: String, headers: Map<String,String> = emptyMap()) {
                val b = Request.Builder().url(staging).header("Authorization", Credentials.basic(config.username, config.password))
                headers.forEach { (k,v) -> b.header(k,v) }
                client.newCall(b.method(method, if (method == "PUT") "staged".toRequestBody() else null).build()).execute().use { report("temp-$method code=${it.code}") }
            }
            try {
                temp("PUT")
                temp("MOVE", mapOf("Destination" to url, "Overwrite" to "F"))
                temp("MOVE", mapOf("Destination" to url + ".new", "Overwrite" to "F"))
                client.newCall(Request.Builder().url(url + ".new").header("Authorization", Credentials.basic(config.username, config.password)).delete().build()).execute().close()
            } finally { temp("DELETE") }
            call("PROPFIND", """<d:propfind xmlns:d="DAV:"><d:allprop/></d:propfind>""", mapOf("Depth" to "0", "Content-Type" to "application/xml"))
        } finally { call("DELETE") }
    }

    @Test fun realSyncRoundTripAndConflictProtection() = kotlinx.coroutines.runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifySync") == "true")
        val inst = InstrumentationRegistry.getInstrumentation()
        fun report(text: String) = inst.sendStatus(0, android.os.Bundle().apply { putString("stream", "SYNC_DIAG $text\n") })
        val context = inst.targetContext
        val local = if (InstrumentationRegistry.getArguments().getString("localDav") == "true") LocalDavServer() else null
        val saved = local?.config ?: CloudSettings(context).load()
        val token = java.util.UUID.randomUUID().toString()
        val base = saved.url.trim().trimEnd('/') + "/DailyOne/verify-$token/"
        val config = saved.copy(url = base)
        val client = local?.client ?: OkHttpClient.Builder().followRedirects(false).build()
        fun engine(store: JournalStore) = SyncEngine(store) { DavClient(it, client) }
        fun directory(method: String) = client.newCall(Request.Builder().url(base)
            .header("Authorization", Credentials.basic(saved.username, saved.password)).method(method, null).build()).execute()
        directory("MKCOL").use { report("test-parent-create=${it.code}"); check(it.isSuccessful) { "MKCOL failed: ${it.code}" } }
        val firstDb = "dav-first-$token.db"
        val secondDb = "dav-second-$token.db"
        val first = JournalStore(context, firstDb, "dav-first-$token")
        val second = JournalStore(context, secondDb, "dav-second-$token")
        try {
            first.load(); second.load()
            val photo = "$token.png"
            val bytes = byteArrayOf(1,2,3,4)
            java.io.File(first.mediaDir, photo).writeBytes(bytes)
            val entry = Entry(body = "device sync probe", photos = listOf(photo))
            first.write(entry)
            val percentages = mutableListOf<Int>()
            var injected = false
            check(engine(first).sync(config) { state ->
                percentages += state.percent
                if (state.stage == "准备上传" && !injected && InstrumentationRegistry.getArguments().getString("verifyConcurrentEditing") == "true") {
                    injected = true
                    // The upload snapshot already exists; saving must not wait on network I/O.
                    kotlinx.coroutines.runBlocking { first.save(entry.copy(body = "edited during upload")) }
                }
            } == 0)
            check(percentages.first() == 0 && percentages.last() == 100 && percentages.zipWithNext().all { it.first <= it.second })
            if (injected) {
                check(first.get(entry.id)?.dirty == true)
                check(first.get(entry.id)?.body == "edited during upload")
                val baseline = first.get(entry.id)?.etag
                check(baseline != null)
                first.save(entry.copy(body = entry.body)) // A draft still carries the pre-sync ETag.
                check(first.get(entry.id)?.etag == baseline)
                engine(first).sync(config)
                report("edit-during-upload-and-stale-draft-baseline=OK")
            }
            check(first.get(entry.id)?.dirty == false)
            report("initial-upload=OK")
            check(engine(second).sync(config) == 0)
            check(second.get(entry.id)?.body == entry.body)
            check(java.io.File(second.mediaDir, photo).readBytes().contentEquals(bytes))
            report("second-device-download-and-media=OK")
            val stale = first.get(entry.id)!!
            val peer = second.get(entry.id)!!
            second.write(peer.copy(body = "peer edit", dirty = true))
            engine(second).sync(config)
            val dav = DavClient(config, client)
            org.junit.Assert.assertThrows(java.io.IOException::class.java) { dav.putEntry(stale.copy(body = "stale edit")) }
            org.junit.Assert.assertThrows(java.io.IOException::class.java) { dav.putEntry(entry) }
            check(dav.getEntry(entry.id).body == "peer edit")
            report("stale-update-and-create-collision-protection=OK")
            first.write(stale.copy(body = "local concurrent edit", dirty = true))
            check(engine(first).sync(config) == 1)
            check(first.get(entry.id)?.body == "peer edit")
            check(first.entries.value.any { it.body == "local concurrent edit" })
            check(engine(first).sync(config) == 0)
            report("conflict-copy-and-repeat-sync=OK")
            // Confirm an existing immutable attachment cannot be overwritten.
            dav.putPhoto(photo, byteArrayOf(9,9))
            val downloaded = java.io.File(first.mediaDir, "verify-download")
            dav.downloadPhoto(photo, downloaded)
            check(downloaded.readBytes().contentEquals(bytes))
            report("attachment-overwrite-protection=OK")
            val photoUploads = java.util.concurrent.atomic.AtomicInteger()
            val counting = client.newBuilder().addInterceptor { chain ->
                if (chain.request().method == "PUT" && chain.request().url.encodedPath.contains("/photos/")) photoUploads.incrementAndGet()
                chain.proceed(chain.request())
            }.build()
            first.write(first.get(entry.id)!!.copy(body = "only document changed", dirty = true))
            SyncEngine(first) { DavClient(it, counting) }.sync(config)
            check(photoUploads.get() == 0)
            val uncertain = first.get(entry.id)!!.copy(body = "upload acknowledged by server only", dirty = true)
            first.write(uncertain)
            dav.putEntry(uncertain) // Simulate losing the successful write response locally.
            check(engine(first).sync(config) == 0)
            check(first.get(entry.id)?.dirty == false)
            report("completed-media-skipped-and-lost-ack-reconciled=OK")
        } finally {
            first.close(); second.close()
            context.deleteDatabase(firstDb); context.deleteDatabase(secondDb)
            first.mediaDir.deleteRecursively(); second.mediaDir.deleteRecursively()
            directory("DELETE").use { check(it.isSuccessful) }
            report("verification-directory-cleaned=OK")
            local?.close()
        }
    }

    @Test fun cleanupInterruptedVerification() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("cleanupVerification") == "true")
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val saved = CloudSettings(context).load()
        val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        var cleaned = 0
        // Only directories linked to databases created by this verification test.
        for (name in context.databaseList()) {
            val token = name.removePrefix("dav-first-").removeSuffix(".db")
            if (name != "dav-first-$token.db" || !validId(token)) continue
            val url = saved.url.trim().trimEnd('/') + "/DailyOne/verify-$token/"
            val request = Request.Builder().url(url).header("Authorization", Credentials.basic(saved.username, saved.password)).delete().build()
            client.newCall(request).execute().use { check(it.isSuccessful || it.code == 404) }
            for (side in listOf("first", "second")) {
                context.deleteDatabase("dav-$side-$token.db")
                java.io.File(context.filesDir, "dav-$side-$token").deleteRecursively()
            }
            cleaned++
        }
        inst.sendStatus(0, android.os.Bundle().apply { putString("stream", "SYNC_DIAG interrupted-verifications-cleaned=$cleaned\n") })
    }

    @Test fun backgroundServiceAllowsEditingAndSurvivesActivityClose() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyBackgroundSync") == "true")
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val local = if (InstrumentationRegistry.getArguments().getString("localDav") == "true") LocalDavServer() else null
        val originalPending = SyncRuntime.token(context)?.let { SyncRuntime.config(context) }
        if (local != null) kotlinx.coroutines.runBlocking { SyncRuntime.cancel(context) }
        val saved = local?.config ?: CloudSettings(context).load()
        val token = java.util.UUID.randomUUID().toString()
        val base = saved.url.trim().trimEnd('/') + "/DailyOne/verify-$token/"
        val client = local?.client ?: OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        fun directory(method: String) = client.newCall(Request.Builder().url(base).header("Authorization", Credentials.basic(saved.username, saved.password)).method(method, null).build()).execute()
        directory("MKCOL").use { check(it.isSuccessful) }
        val db = "dav-background-$token.db"
        val isolated = JournalStore(context, db, "dav-background-$token")
        val field = SyncRuntime::class.java.getDeclaredField("journal").apply { isAccessible = true }
        val previous = field.get(SyncRuntime)
        var scenario: androidx.test.core.app.ActivityScenario<MainActivity>? = null
        val edits = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
        var injected: kotlinx.coroutines.Job? = null
        var injectionError: Throwable? = null
        var retriedToken: String? = null
        val verifyRetry = InstrumentationRegistry.getArguments().getString("verifyRetry") == "true"
        val failFirst = java.util.concurrent.atomic.AtomicBoolean(verifyRetry)
        val previousFactory = SyncRuntime.davFactory
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val flaky = client.newBuilder().retryOnConnectionFailure(false).addInterceptor { chain ->
            if (failFirst.compareAndSet(true, false)) throw java.io.IOException("simulated connection interruption")
            requests.add(chain.request().method)
            chain.proceed(chain.request())
        }.build()
        fun await(condition: () -> Boolean) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 180_000
            while (!condition()) { check(android.os.SystemClock.elapsedRealtime() < deadline) { "Background verification timed out" }; Thread.sleep(100) }
        }
        try {
            kotlinx.coroutines.runBlocking { isolated.load() }
            val entry = Entry(body = "background test")
            isolated.write(entry)
            repeat(2) { isolated.write(Entry(body = "additional background test")) }
            field.set(SyncRuntime, isolated)
            SyncRuntime.davFactory = { DavClient(it, flaky) }
            scenario = androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
            var vm: JournalViewModel? = null
            scenario.onActivity { vm = androidx.lifecycle.ViewModelProvider(it)[JournalViewModel::class.java] }
            await { vm?.loading == false }
            val added = Entry(body = "new entry created during sync")
            val renamed = isolated.notebooks.value.first().copy(name = "同步期间重命名", dirty = true)
            scenario.onActivity { vm!!.open(entry) }
            await { vm?.draft?.id == entry.id }
            scenario.onActivity {
                injected = edits.launch {
                    try {
                        kotlinx.coroutines.withTimeout(150_000) {
                            SyncRuntime.progress.first { it.running && it.stage == "准备上传" }
                        }
                        // The first round's upload snapshots have already been taken.
                        vm!!.change(entry.copy(body = "foreground editing stays available"))
                        isolated.save(added)
                        isolated.saveNotebook(renamed)
                    } catch (e: Throwable) { injectionError = e }
                }
                SyncRuntime.start(it, saved.copy(url = base))
                check(SyncRuntime.progress.value.running || SyncRuntime.progress.value.waiting)
                check(vm?.busy == false)
            }
            if (verifyRetry) {
                await { SyncRuntime.progress.value.waiting && SyncRuntime.progress.value.message?.contains("simulated connection interruption") == true }
                val queued = SyncRuntime.token(context)
                check(queued != null)
                retriedToken = queued
                scenario.close()
                SyncRuntime.progress.value = SyncProgress() // Lose transient UI state as on a cold start.
                scenario = androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
                scenario.onActivity { vm = androidx.lifecycle.ViewModelProvider(it)[JournalViewModel::class.java] }
                await { vm?.loading == false }
                check(SyncRuntime.token(context) == queued)
                scenario.onActivity { vm!!.open(entry) }
                await { vm?.draft?.id == entry.id }
            }
            await { injected?.isCompleted == true && vm?.saved == true }
            check(injectionError == null) { injectionError?.stackTraceToString().orEmpty() }
            check(isolated.get(entry.id)?.body == "foreground editing stays available")
            check(SyncRuntime.progress.value.running)
            scenario.close(); scenario = null
            await { !SyncRuntime.progress.value.running && !SyncRuntime.progress.value.waiting }
            check(SyncRuntime.progress.value.percent == 100) { SyncRuntime.progress.value.message.orEmpty() }
            check(isolated.get(entry.id)?.body == "foreground editing stays available")
            check(isolated.entries.value.none { it.dirty && it.syncable })
            check(isolated.notebooks.value.none { it.dirty })
            val dav = DavClient(saved.copy(url = base), client)
            check(dav.getEntry(entry.id).body == "foreground editing stays available")
            check(dav.getEntry(added.id).body == added.body)
            check(dav.getNotebook(renamed.id).name == renamed.name)
            if (verifyRetry) {
                await { androidx.work.WorkManager.getInstance(context).getWorkInfosForUniqueWork("dailyone_sync_$retriedToken").get().any { it.state == androidx.work.WorkInfo.State.SUCCEEDED && it.runAttemptCount > 0 } }
                check(SyncRuntime.token(context) == null)
            }
            val reopened = JournalStore(context, db, "dav-background-$token")
            try {
                kotlinx.coroutines.runBlocking {
                    reopened.load()
                    requests.clear()
                    SyncEngine(reopened, SyncRuntime.davFactory).sync(saved.copy(url = base))
                    check(requests.none { it == "GET" || it == "PUT" || it == "MOVE" })
                }
            } finally { reopened.close() }
            inst.sendStatus(0, android.os.Bundle().apply { putString("stream", "SYNC_DIAG durable-retry-reopen-auto-drain-and-etag-skip=OK retryTest=$verifyRetry\n") })
        } finally {
            edits.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            scenario?.close()
            kotlinx.coroutines.runBlocking { SyncRuntime.cancel(context) }
            await { !SyncRuntime.progress.value.running && !SyncRuntime.progress.value.waiting }
            field.set(SyncRuntime, previous)
            SyncRuntime.davFactory = previousFactory
            isolated.close(); context.deleteDatabase(db); isolated.mediaDir.deleteRecursively()
            directory("DELETE").use { check(it.isSuccessful || it.code == 404) }
            local?.close()
            if (local != null && originalPending != null) SyncRuntime.start(context, originalPending)
        }
    }

    @Test fun pauseContinueAndStopPersistAndCancelNetworkRequests() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyPause") == "true")
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val originalPending = SyncRuntime.token(context)?.let { SyncRuntime.config(context) }
        val originallyPaused = SyncRuntime.isPaused(context)
        kotlinx.coroutines.runBlocking { SyncRuntime.cancel(context) }
        val fixture = LocalDavServer()
        val token = java.util.UUID.randomUUID().toString()
        val store = JournalStore(context, "dav-controls-$token.db", "dav-controls-$token")
        val field = SyncRuntime::class.java.getDeclaredField("journal").apply { isAccessible = true }
        val previous = field.get(SyncRuntime)
        val factory = SyncRuntime.davFactory
        fun await(condition: () -> Boolean) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
            while (!condition()) { check(android.os.SystemClock.elapsedRealtime() < deadline) { "Sync control timeout" }; Thread.sleep(50) }
        }
        try {
            kotlinx.coroutines.runBlocking { store.load() }
            val entry = Entry(body = "pause and continue fixture")
            store.write(entry)
            field.set(SyncRuntime, store)
            val requested = java.util.concurrent.atomic.AtomicInteger()
            val client = fixture.client.newBuilder().addInterceptor { chain -> requested.incrementAndGet(); chain.proceed(chain.request()) }.build()
            SyncRuntime.davFactory = { DavClient(it, client) }
            fixture.stallNext.set(true)
            SyncRuntime.start(context, fixture.config)
            await { fixture.stalled.get() > 0 && SyncRuntime.progress.value.running }
            val queued = SyncRuntime.token(context)
            val started = android.os.SystemClock.elapsedRealtime()
            kotlinx.coroutines.runBlocking { SyncRuntime.pause(context) }
            check(android.os.SystemClock.elapsedRealtime() - started < 10_000) { "Pause did not cancel the stalled network call" }
            check(SyncRuntime.progress.value.paused && !SyncRuntime.progress.value.controlling)
            check(SyncRuntime.token(context) == queued && SyncRuntime.isPaused(context))
            SyncRuntime.progress.value = SyncProgress()
            SyncRuntime.resume(context)
            check(SyncRuntime.progress.value.paused && !SyncRuntime.progress.value.waiting)
            check(androidx.work.WorkManager.getInstance(context).getWorkInfosForUniqueWork("dailyone_sync_$queued").get().all { it.state.isFinished })
            kotlinx.coroutines.runBlocking { SyncRuntime.continueSync(context) }
            await { SyncRuntime.progress.value.percent == 100 && SyncRuntime.token(context) == null }
            check(store.get(entry.id)?.dirty == false)
            check(DavClient(fixture.config, fixture.client).getEntry(entry.id).body == entry.body)
            val completedTag = store.get(entry.id)?.etag
            store.write(store.get(entry.id)!!.copy(body = "stop preserves unsynced edit", dirty = true))
            fixture.stallNext.set(true)
            val before = fixture.stalled.get()
            SyncRuntime.start(context, fixture.config)
            await { fixture.stalled.get() > before && SyncRuntime.progress.value.running }
            kotlinx.coroutines.runBlocking { SyncRuntime.cancel(context) }
            check(SyncRuntime.token(context) == null)
            SyncRuntime.resume(context)
            check(!SyncRuntime.progress.value.running && !SyncRuntime.progress.value.waiting && !SyncRuntime.progress.value.paused)
            check(store.get(entry.id)?.body == "stop preserves unsynced edit")
            check(store.get(entry.id)?.etag == completedTag && store.get(entry.id)?.dirty == true)
            inst.sendStatus(0, android.os.Bundle().apply { putString("stream", "SYNC_DIAG pause-persistence-continue-stop-and-network-cancel=OK\n") })
        } finally {
            kotlinx.coroutines.runBlocking { SyncRuntime.cancel(context) }
            field.set(SyncRuntime, previous); SyncRuntime.davFactory = factory
            store.close(); context.deleteDatabase("dav-controls-$token.db"); store.mediaDir.deleteRecursively()
            fixture.close()
            if (originalPending != null) SyncRuntime.start(context, originalPending, initiallyPaused = originallyPaused)
        }
    }

}
