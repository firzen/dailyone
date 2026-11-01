package com.firzen.dailyone

import android.content.Context

/** Separate encrypted preferences and Android Keystore alias; excluded from journal sync. */
class MapSettings(context: Context, preferenceName: String = "place_credentials", keyAlias: String = "dailyone.places") {
    private val encrypted = CloudSettings(context, preferenceName, keyAlias)
    private val useBuildDefaults = preferenceName == "place_credentials" && keyAlias == "dailyone.places"
    fun loadAmapKey(): String = encrypted.load().password.ifBlank { if (useBuildDefaults) BuildConfig.AMAP_WEB_KEY else "" }
    fun saveAmapKey(value: String) {
        val key = value.trim()
        require(key.isEmpty() || validAmapKey(key)) { "请填写 32 位高德 Web服务 Key" }
        encrypted.save(encrypted.load().copy(password = key))
    }
    fun loadAndroidKey(): String = encrypted.load().username.ifBlank { if (useBuildDefaults) BuildConfig.AMAP_ANDROID_KEY else "" }
    fun saveAndroidKey(value: String) {
        val key = value.trim()
        require(key.isEmpty() || validAmapKey(key)) { "请填写 32 位高德 Android平台 Key" }
        encrypted.save(encrypted.load().copy(username = key))
    }

    fun loadGoogleKey(): String = encrypted.load().url.ifBlank { if (useBuildDefaults) BuildConfig.GOOGLE_PLACES_KEY else "" }
    fun saveGoogleKey(value: String) {
        val key = value.trim()
        require(key.isEmpty() || validGoogleKey(key)) { "请填写有效的 Google 地点服务 Key" }
        encrypted.save(encrypted.load().copy(url = key))
    }
}
internal fun validAmapKey(value: String): Boolean = Regex("[0-9a-fA-F]{32}").matches(value)
