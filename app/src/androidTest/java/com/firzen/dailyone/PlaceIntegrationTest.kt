package com.firzen.dailyone

import android.location.Address
import android.content.Intent
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.UUID

class PlaceIntegrationTest {
    @Test fun namesPersistWithoutMovingPhotosOrOverwritingNewerEdits() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = UUID.randomUUID().toString(); val store = JournalStore(context, "$key.db", key)
        try {
            store.load()
            val photo = "${UUID.randomUUID()}.jpg"; val point = GeoPoint(31.0, 121.0)
            val original = Entry(title = "保留标题", body = "原文", photos = listOf(photo), photoLocations = mapOf(photo to point), createdAt = 1234L)
            store.save(original)
            val named = point.copy(name = "咖啡店", address = "测试路1号")
            store.nameLocation(original.id, point, named)
            assertEquals(named, store.get(original.id)?.location)
            // A text edit from an older editor snapshot must retain the resolved name.
            store.save(original.copy(body = "后写的内容"))
            val saved = requireNotNull(store.get(original.id))
            assertEquals(named, saved.location); assertEquals(original.photos, saved.photos)
            assertEquals(1234L, saved.createdAt); assertEquals("后写的内容", saved.body)
            val custom = point.copy(name = "我选择的店名")
            store.save(saved.copy(recordedLocation = custom))
            store.nameLocation(saved.id, point, named.copy(name = "其他名称"))
            assertEquals(custom, store.get(saved.id)?.recordedLocation)
        } finally { store.close(); context.deleteDatabase("$key.db"); store.mediaDir.deleteRecursively() }
    }
    @Test fun addressConvertsToNamedPlaceAndRetainsStreetAddress() {
        val address = Address(Locale.SIMPLIFIED_CHINESE).apply {
            latitude = 23.1; longitude = 113.3; featureName = "咖啡店"; setAddressLine(0, "广州市测试路1号")
        }
        val point = requireNotNull(address.toPlace())
        assertEquals("咖啡店", point.label()); assertEquals("广州市测试路1号", point.address)
        assertEquals(23.1, point.latitude, 0.0)
    }
    @Test fun sharingGrantsOnlyReadAccessToTheUnmodifiedOriginal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(File(context.filesDir, "photos").apply { mkdirs() }, "${UUID.randomUUID()}.png")
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        try {
            file.writeBytes(bytes)
            val intent = photoShareIntent(context, file)
            assertEquals(Intent.ACTION_SEND, intent.action); assertEquals("image/png", intent.type)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            @Suppress("DEPRECATION") val uri = requireNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            assertEquals("content", uri.scheme); assertEquals(uri, intent.clipData?.getItemAt(0)?.uri)
            assertArrayEquals(bytes, context.contentResolver.openInputStream(uri)?.use { it.readBytes() })
            assertThrows(IllegalArgumentException::class.java) { photoShareIntent(context, File(context.filesDir, "journal.db")) }
        } finally { file.delete() }
    }
    @Test fun deviceAmapSearchWorksInMainlandHongKongAndMacao() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live-place-lookup") == "true")
        val service = PlaceService(InstrumentationRegistry.getInstrumentation().targetContext)
        for ((region, query, city) in listOf(Triple(PlaceRegion.MAINLAND, "广州塔", "广州"), Triple(PlaceRegion.HONG_KONG, "星巴克", ""), Triple(PlaceRegion.MACAO, "大三巴牌坊", ""))) {
            val results = service.search(query, null, region, city)
            assertTrue("No results for ${region.name}", results.isNotEmpty())
            assertTrue(results.all { it.source == "AMap" && it.countryCode == region.countryCode && it.coordinateSystem == CoordinateSystem.GCJ02 })
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                putString("amap_live_result", "PASS ${region.name}: ${results.size} public POI results")
            })
        }
    }
}
