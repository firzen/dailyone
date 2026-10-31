package com.firzen.dailyone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

internal fun journalMarkerBitmap(count: Int, density: Float): Bitmap {
    val size = (44 * density).toInt().coerceAtLeast(44)
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val center = size / 2f
        paint.color = Color.WHITE
        canvas.drawCircle(center, center, center - 1, paint)
        paint.color = Color.rgb(22, 140, 204)
        canvas.drawCircle(center, center, center - 3 * density, paint)
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = (if (count > 99) 12 else 16) * density
        paint.isFakeBoldText = true
        canvas.drawText(count.toString(), center, center - (paint.ascent() + paint.descent()) / 2, paint)
    }
}
