package com.firzen.dailyone

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyStore
import java.util.UUID

class MapSettingsTest {
    @Test fun credentialsAreEncryptedAndSurviveReopeningWithoutEnteringJournalData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "map-test-${UUID.randomUUID()}"; val alias = "dailyone.$namespace"
        val prefs = context.getSharedPreferences(namespace, android.content.Context.MODE_PRIVATE)
        try {
            val key = "1234567890abcdef1234567890abcdef"
            MapSettings(context, namespace, alias).saveAmapKey(key)
            MapSettings(context, namespace, alias).saveAndroidKey("a".repeat(32))
            assertEquals(key, MapSettings(context, namespace, alias).loadAmapKey())
            assertEquals("a".repeat(32), MapSettings(context, namespace, alias).loadAndroidKey())
            assertFalse(prefs.getString("config", "")!!.contains(key))
            assertTrue(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
        } finally {
            prefs.edit().clear().commit()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }
    /** Explicit opt-in installation helper; ordinary test runs never change live credentials. */
    @Test fun configureLiveKeyOnlyWhenExplicitlyRequested() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("configure-amap-key") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val web = java.io.File(context.cacheDir, "amap-bootstrap-key")
        val androidFile = java.io.File(context.cacheDir, "amap-android-bootstrap-key")
        try {
            check(web.exists() || androidFile.exists())
            if (web.exists()) {
                val provided = web.readText().trim(); MapSettings(context).saveAmapKey(provided)
                assertTrue("Encrypted web credential did not persist", MapSettings(context).loadAmapKey() == provided)
            }
            if (androidFile.exists()) {
                val provided = androidFile.readText().trim(); MapSettings(context).saveAndroidKey(provided)
                assertTrue("Encrypted Android credential did not persist", MapSettings(context).loadAndroidKey() == provided)
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("amap_key_configured", "true") })
        } finally { web.delete(); androidFile.delete() }
    }

}
