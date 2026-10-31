package com.firzen.dailyone

import android.content.Context

/** Separate encrypted preferences and Android Keystore alias; excluded from journal sync. */
class MapSettings(context: Context, preferenceName: String = "place_credentials", keyAlias: String = "dailyone.places") {
    private val encrypted = CloudSettings(context, preferenceName, keyAlias)
    fun loadAmapKey(): String = encrypted.load().password
    fun saveAmapKey(value: String) {
        val key = value.trim()
        require(key.isEmpty() || validAmapKey(key)) { "请填写 32 位高德 Web服务 Key" }
        encrypted.save(encrypted.load().copy(password = key))
    }
    fun loadAndroidKey(): String = encrypted.load().username
    fun saveAndroidKey(value: String) {
        val key = value.trim()
        require(key.isEmpty() || validAmapKey(key)) { "请填写 32 位高德 Android平台 Key" }
        encrypted.save(encrypted.load().copy(username = key))
    }

}
internal fun validAmapKey(value: String): Boolean = Regex("[0-9a-fA-F]{32}").matches(value)
