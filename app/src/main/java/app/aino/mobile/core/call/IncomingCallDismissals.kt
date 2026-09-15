package app.aino.mobile.core.call

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object IncomingCallDismissals {
    private val _events = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()
    fun dismiss(callId: Long) { _events.tryEmit(callId) }
}