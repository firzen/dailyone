package com.firzen.dailyone

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

internal fun photoShareIntent(context: Context, file: File): Intent {
    require(validPhoto(file.name) && file.canonicalFile.parentFile == File(context.filesDir, "photos").canonicalFile) { "无法分享这张图片" }
    check(file.isFile && file.length() > 0) { "图片文件不存在，请先完成同步" }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.photos", file)
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "image/*"
    return Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("原图", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
