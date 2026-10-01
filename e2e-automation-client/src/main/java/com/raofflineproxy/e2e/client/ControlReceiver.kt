package com.raofflineproxy.e2e.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

private const val TAG = "E2eAutomationClient"
private val PROVIDER_URI: Uri = Uri.parse("content://com.raofflineproxy.config")

/** `adb shell am broadcast -n com.raofflineproxy.e2e.client/.ControlReceiver --es method start --ei watch 8`
 *  calls the provider, counts change notifications for `watch` seconds and returns the outcome
 *  as the broadcast's result data. */
class ControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val method = intent.getStringExtra("method") ?: "status"
        val watchSeconds = intent.getIntExtra("watch", 0)
        val pending = goAsync()
        thread {
            val resolver = context.contentResolver
            val changes = AtomicInteger()
            val handlerThread = HandlerThread("observer").apply { start() }
            val observer = object : ContentObserver(Handler(handlerThread.looper)) {
                override fun onChange(selfChange: Boolean) {
                    Log.d(TAG, "onChange")
                    changes.incrementAndGet()
                }
            }
            resolver.registerContentObserver(PROVIDER_URI, false, observer)
            val outcome = runCatching { resolver.call(PROVIDER_URI, method, null, null) }
                .fold(
                    { bundle -> "result=${bundle?.getString("result")} status=${bundle?.getString("status")}" },
                    { error -> "error=${error.javaClass.simpleName}: ${error.message}" }
                )
            if (watchSeconds > 0) Thread.sleep(watchSeconds * 1000L)
            val after = if (watchSeconds > 0) {
                val statusAfter = runCatching { resolver.call(PROVIDER_URI, "status", null, null)?.getString("status") }.getOrNull()
                Thread.sleep(500)
                " changes=${changes.get()} statusAfter=$statusAfter"
            } else {
                ""
            }
            resolver.unregisterContentObserver(observer)
            handlerThread.quitSafely()
            val text = "$method -> $outcome$after"
            Log.i(TAG, text)
            pending.resultData = text
            pending.finish()
        }
    }
}
