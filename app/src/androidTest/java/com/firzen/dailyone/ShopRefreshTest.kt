package com.firzen.dailyone

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ShopRefreshTest {
    @Test fun refreshesOnlyLocationsPreservingPhotosDatesTextAndCustomNames() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = UUID.randomUUID().toString(); val store = JournalStore(context, "$key.db", key)
        try {
            store.load()
            val point = GeoPoint(23.1, 113.3, "旧街道", countryCode = "CN")
            val photo = "${UUID.randomUUID()}.jpg"
            val original = Entry(title = "保留标题", body = "保留正文", createdAt = 1234L, photos = listOf(photo), photoLocations = mapOf(photo to point))
            val manual = Entry(recordedLocation = point.copy(name = "我填写的店名", nameOrigin = PlaceNameOrigin.CUSTOM), candidate = true, dirty = false)
            store.save(original); store.save(manual)
            val worker = JournalShopRefresh(store) { p -> p.copy(name = "附近咖啡店", address = "新地址", source = "AMap", poiId = "public-fixture", nameOrigin = PlaceNameOrigin.AUTO) }
            val result = worker.run {}
            assertEquals(2, result.updated)
            val saved = requireNotNull(store.get(original.id))
            assertEquals("附近咖啡店", saved.location?.name); assertEquals(original.photos, saved.photos)
            assertEquals(original.createdAt, saved.createdAt); assertEquals(original.body, saved.body)
            assertTrue(point.sameCoordinates(requireNotNull(saved.location)))
            store.save(original.copy(body = "更新期间继续输入的正文"))
            assertEquals("附近咖啡店", store.get(original.id)?.location?.name)
            assertEquals("更新期间继续输入的正文", store.get(original.id)?.body)
            val named = requireNotNull(store.get(manual.id))
            assertEquals("我填写的店名", named.location?.name); assertEquals("新地址", named.location?.address)
            assertTrue(named.candidate); assertFalse(named.dirty)
        } finally { store.close(); context.deleteDatabase("$key.db"); store.mediaDir.deleteRecursively() }
    }
    @Test fun stoppingKeepsCommittedResultsAndRejectsConcurrentLocationChanges() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = UUID.randomUUID().toString(); val store = JournalStore(context, "$key.db", key)
        try {
            store.load()
            val point = GeoPoint(23.1, 113.3, countryCode = "CN")
            val first = Entry(recordedLocation = point); val second = Entry(recordedLocation = point.copy(latitude = 23.2))
            store.save(first); store.save(second)
            lateinit var worker: JournalShopRefresh
            worker = JournalShopRefresh(store) { p -> worker.stop.set(true); p.copy(name = "已更新", nameOrigin = PlaceNameOrigin.AUTO) }
            assertEquals(1, worker.run {}.checked)
            assertEquals(1, store.entries.value.count { it.location?.name == "已更新" })
            val before = requireNotNull(store.get(first.id))
            val moved = point.copy(latitude = 25.0, name = "后来选择的位置")
            store.save(before.copy(recordedLocation = moved))
            assertFalse(store.refreshShopLocation(first.id, point, point.copy(name = "迟到的结果")))
            assertEquals(moved, store.get(first.id)?.location)
        } finally { store.close(); context.deleteDatabase("$key.db"); store.mediaDir.deleteRecursively() }
    }
    /** Explicit task runner: invokes the same live ViewModel action as the settings button. */
    @Test fun refreshLiveJournalsOnlyWhenExplicitlyRequested() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("refresh-live-journals") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: JournalViewModel
            scenario.onActivity { vm = androidx.lifecycle.ViewModelProvider(it)[JournalViewModel::class.java] }
            val readyDeadline = android.os.SystemClock.elapsedRealtime() + 30_000
            while (vm.loading && android.os.SystemClock.elapsedRealtime() < readyDeadline) Thread.sleep(100)
            check(!vm.loading) { "Journal data did not finish loading" }
            scenario.onActivity { vm.startShopRefresh() }
            instrumentation.waitForIdleSync()
            check(vm.shopProgress.running || vm.shopProgress.total > 0) { "Shop refresh did not start" }
            var lastChecked = -1
            val deadline = android.os.SystemClock.elapsedRealtime() + 20 * 60_000
            while (vm.shopProgress.running) {
                val state = vm.shopProgress
                if (state.checked != lastChecked && (state.checked % 5 == 0 || lastChecked < 0)) {
                    instrumentation.sendStatus(0, android.os.Bundle().apply {
                        putString("shop_refresh_progress", "${state.checked}/${state.total}; updated=${state.updated}; shops=${state.withShop}; skipped=${state.missing}; failed=${state.failed}")
                    })
                    lastChecked = state.checked
                }
                if (android.os.SystemClock.elapsedRealtime() > deadline) {
                    scenario.onActivity { vm.stopShopRefresh() }
                    error("Shop refresh timed out; committed updates have been retained")
                }
                Thread.sleep(500)
            }
            val result = vm.shopProgress
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putString("shop_refresh_result", "checked=${result.checked}; total=${result.total}; updated=${result.updated}; shops=${result.withShop}; skipped=${result.missing}; failed=${result.failed}")
            })
            check(result.checked == result.total) { "Shop refresh stopped before all entries were checked" }
        }
    }

}
