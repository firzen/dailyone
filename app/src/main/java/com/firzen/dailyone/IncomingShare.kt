package com.firzen.dailyone

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/** Keep order and accept ClipData used by some gallery providers. */
internal fun sharedMediaUris(intent: Intent): List<Uri> {
    if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return emptyList()
    val streams = if (intent.action == Intent.ACTION_SEND_MULTIPLE)
        IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
    else listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
    val clips = intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }.orEmpty()
    return (streams + clips).filter { it.scheme == "content" }.distinct()
}
