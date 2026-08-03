package com.raofflineproxy.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ProxyRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ProxyService.shouldKeepRunning(context)) {
            return
        }

        Log.i("RAProxy/ProxyRestartReceiver", "Restarting proxy service after unexpected shutdown")
        ProxyService.start(context)
    }
}
