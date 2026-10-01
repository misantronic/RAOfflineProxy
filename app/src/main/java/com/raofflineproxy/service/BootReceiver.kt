package com.raofflineproxy.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.raofflineproxy.PrefsConstants

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val shouldRestartProxy = prefs.getBoolean(PrefsConstants.KEY_AUTOSTART_PROXY, false) || ProxyService.shouldKeepRunning(context)
        if (!shouldRestartProxy) return

        HeadlessProxy.start(context)
    }
}
