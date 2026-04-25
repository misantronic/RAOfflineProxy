package com.raofflineproxy

import android.util.Log
import com.raofflineproxy.ui.SnackbarManager

private const val TAG = "RAProxy/RequestError"

object RequestFailureNotifier {
    fun report(message: String, logDetails: String? = null) {
        if (logDetails.isNullOrBlank()) {
            Log.e(TAG, message)
        } else {
            Log.e(TAG, "$message | $logDetails")
        }
        SnackbarManager.showError(message)
    }
}
