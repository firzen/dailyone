package com.firzen.dailyone

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.security.MessageDigest

enum class CloudProvider(val label: String) { WEBDAV("WebDAV"), S3("S3") }

data class S3Config(val endpoint: String = "", val region: String = "", val bucket: String = "",
    val accessKey: String = "", val secretKey: String = "", val prefix: String = "DailyOne/", val pathStyle: Boolean = false) {
    fun normalized() = copy(endpoint = endpoint.trim().trimEnd('/'), region = region.trim(), bucket = bucket.trim(),
        accessKey = accessKey.trim(), prefix = prefix.trim().trim('/').let { if (it.isEmpty()) "" else "$it/" })
    val configured get() = endpoint.isNotBlank() && region.isNotBlank() && bucket.isNotBlank() && accessKey.isNotBlank() && secretKey.isNotBlank()
    fun validate() {
        val url = endpoint.toHttpUrl()
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "请使用不含账号或查询参数的 HTTPS S3 地址" }
        require(configured) { "请填写 S3 地址、Region、Bucket 和访问密钥" }
        require(Regex("[a-zA-Z0-9-]+").matches(region)) { "Region 格式不正确" }
        require(Regex("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]").matches(bucket) && !bucket.contains("..")) { "请输入有效的 Bucket 名称" }
        require(prefix.split('/').none { it == "." || it == ".." } && prefix.none { it.isISOControl() || it == '\\' }) { "存储前缀格式不正确" }
    }
    fun json() = JSONObject().put("endpoint", endpoint).put("region", region).put("bucket", bucket)
        .put("accessKey", accessKey).put("secretKey", secretKey).put("prefix", prefix).put("pathStyle", pathStyle)
}

// Keep the first three positional arguments compatible with existing WebDAV callers.
data class CloudConfig(val url: String = "", val username: String = "", val password: String = "",
    val provider: CloudProvider = CloudProvider.WEBDAV, val s3: S3Config = S3Config()) {
    val configured get() = when (provider) { CloudProvider.WEBDAV -> url.isNotBlank(); CloudProvider.S3 -> s3.configured }
    fun normalized() = copy(url = url.trim().trimEnd('/'), username = username.trim(), s3 = s3.normalized())
    fun validate() {
        if (provider == CloudProvider.S3) s3.validate() else {
            val base = url.toHttpUrl()
            require(base.isHttps && base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null) { "请使用不含账号或查询参数的 HTTPS WebDAV 地址" }
        }
    }
    // Credentials are not stored in the database identity. Password rotation preserves the baseline.
    fun targetId(): String {
        val c = normalized()
        val fields = if (provider == CloudProvider.WEBDAV) listOf(provider.name, c.url.toHttpUrl().toString().trimEnd('/'), c.username)
            else listOf(provider.name, c.s3.endpoint.toHttpUrl().toString().trimEnd('/'), c.s3.region, c.s3.bucket, c.s3.prefix, c.s3.accessKey)
        val bytes = JSONObject().put("target", org.json.JSONArray(fields)).toString().toByteArray()
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
    fun json() = JSONObject().put("version", 2).put("provider", provider.name).put("url", url)
        .put("username", username).put("password", password).put("s3", s3.json()).toString()
    companion object {
        fun parse(raw: String): CloudConfig {
            val o = JSONObject(raw); val s = o.optJSONObject("s3") ?: JSONObject()
            return CloudConfig(o.optString("url"), o.optString("username").trim(), o.optString("password"),
                CloudProvider.valueOf(o.optString("provider", "WEBDAV")), S3Config(s.optString("endpoint"), s.optString("region"),
                    s.optString("bucket"), s.optString("accessKey"), s.optString("secretKey"), s.optString("prefix", "DailyOne/"), s.optBoolean("pathStyle")))
        }
    }
}
