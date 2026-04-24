package com.raofflineproxy

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object RequestFailureNotifier {
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events = _events.asSharedFlow()

    fun report(message: String) {
        _events.tryEmit(message)
    }
}
