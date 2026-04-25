package com.raofflineproxy

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

private const val TAG = "RAProxy/RequestError"

object RequestFailureNotifier {
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events = _events.asSharedFlow()

    fun report(message: String, logDetails: String? = null) {
        if (logDetails.isNullOrBlank()) {
            Log.e(TAG, message)
        } else {
            Log.e(TAG, "$message | $logDetails")
        }
        _events.tryEmit(message)
    }
}
