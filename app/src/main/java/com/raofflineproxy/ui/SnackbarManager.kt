package com.raofflineproxy.ui

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class SnackbarDuration { Short, Long, Indefinite }

sealed interface SnackbarEvent {
    data class Error(val message: String) : SnackbarEvent

    data class Progress(val message: String?) : SnackbarEvent

    data class Message(
        val message: String,
        val duration: SnackbarDuration = SnackbarDuration.Long
    ) : SnackbarEvent
}

object SnackbarManager {
    private val _events = MutableSharedFlow<SnackbarEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val events = _events.asSharedFlow()

    fun showError(message: String) {
        if (message.isBlank()) return
        _events.tryEmit(SnackbarEvent.Error(message))
    }

    fun showProgress(message: String?) {
        _events.tryEmit(SnackbarEvent.Progress(message?.takeIf { it.isNotBlank() }))
    }

    fun showMessage(message: String, duration: SnackbarDuration = SnackbarDuration.Long) {
        if (message.isBlank()) return
        _events.tryEmit(SnackbarEvent.Message(message, duration))
    }
}
