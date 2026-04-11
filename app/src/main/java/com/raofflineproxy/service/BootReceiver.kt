package com.raofflineproxy.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.raofflineproxy.ui.patchRetroArchCfg

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("ra_proxy_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("autostart_proxy", false)) return
        val treeUri = prefs.getString("saf_tree_uri", null)?.let { Uri.parse(it) }
        val result = patchRetroArchCfg(context, treeUri)
        if (result.success) {
            prefs.edit().putBoolean("hardcore_was_enabled", result.hardcoreWasEnabled).apply()
            ProxyService.start(context)
        }
    }
}
