package com.firzen.dailyone

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

data class ShopRefreshProgress(val running: Boolean = false, val checked: Int = 0, val total: Int = 0,
    val updated: Int = 0, val withShop: Int = 0, val missing: Int = 0, val failed: Int = 0)

class JournalShopRefresh(private val store: JournalStore, private val lookup: suspend (GeoPoint) -> GeoPoint) {
    val stop = AtomicBoolean(false)
    suspend fun run(progress: (ShopRefreshProgress) -> Unit): ShopRefreshProgress = withContext(Dispatchers.IO) {
        val entries = store.entries.value.filterNot { it.deleted }
        val cache = mutableMapOf<String, GeoPoint>()
        var state = ShopRefreshProgress(running = true, total = entries.size)
        progress(state)
        for (entry in entries) {
            if (stop.get()) break
            val original = entry.location
            if (original == null || PlaceRegion.fromPoint(original)?.provider == PlaceProvider.GOOGLE) {
                state = state.copy(missing = state.missing + 1)
            } else try {
                val key = original.coordinateKey() + "|" + original.poiId
                val fresh = cache[key] ?: lookup(original).also { cache[key] = it }
                val changed = store.refreshShopLocation(entry.id, original, fresh)
                state = state.copy(updated = state.updated + if (changed) 1 else 0,
                    withShop = state.withShop + if (fresh.poiId.isNotBlank()) 1 else 0)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: UnsupportedOperationException) { state = state.copy(missing = state.missing + 1) }
            catch (_: Exception) { state = state.copy(failed = state.failed + 1) }
            state = state.copy(checked = state.checked + 1); progress(state)
        }
        state.copy(running = false).also(progress)
    }
}
