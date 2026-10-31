package com.firzen.dailyone

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

internal fun journalTimestamp(year: Int, month: Int, day: Int, hour: Int, minute: Int,
    zone: ZoneId = ZoneId.systemDefault()): Long = LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

@Composable internal fun JournalDateTimeDialog(initial: Long, confirm: (Long) -> Unit, close: () -> Unit) {
    val context = LocalContext.current
    val latestConfirm by rememberUpdatedState(confirm)
    val latestClose by rememberUpdatedState(close)
    DisposableEffect(context) {
        val local = Instant.ofEpochMilli(initial.takeIf { it > 0 } ?: System.currentTimeMillis()).atZone(ZoneId.systemDefault())
        var timeDialog: TimePickerDialog? = null
        val dateDialog = DatePickerDialog(context, { _, year, month, day ->
            timeDialog = TimePickerDialog(context, { _, hour, minute ->
                latestConfirm(journalTimestamp(year, month + 1, day, hour, minute))
            }, local.hour, local.minute, true).apply {
                setTitle("设置日记时间")
                setOnCancelListener { latestClose() }
                show()
            }
        }, local.year, local.monthValue - 1, local.dayOfMonth).apply {
            setTitle("设置日记日期")
            setOnCancelListener { latestClose() }
            show()
        }
        onDispose { dateDialog.dismiss(); timeDialog?.dismiss() }
    }
}
