package com.firzen.dailyone

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

data class JournalNotice(val message: String, val undoId: String? = null)
class JournalNotices {
    private val channel = Channel<JournalNotice>(Channel.CONFLATED)
    val flow = channel.receiveAsFlow()
    suspend fun send(message: String) { channel.send(JournalNotice(message)) }
    fun trySend(message: String) { channel.trySend(JournalNotice(message)) }
    fun withUndo(message: String, token: String) { channel.trySend(JournalNotice(message, token)) }
}
