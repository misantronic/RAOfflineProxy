package com.raofflineproxy.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.raofflineproxy.PrefsConstants
import com.raofflineproxy.ui.patchRetroArchCfg
import androidx.core.content.edit

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val isBootAction = action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_LOCKED_BOOT_COMPLETED
        if (!isBootAction) return

        val prefs = context.getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PrefsConstants.KEY_AUTOSTART_PROXY, false)) return

        val treeUri = PrefsConstants.loadSafUri(context)
        val result = patchRetroArchCfg(context, treeUri)
        if (result.success) {
            prefs.edit {
                putBoolean(
                    PrefsConstants.KEY_HARDCORE_WAS_ENABLED,
                    result.hardcoreWasEnabled
                )
            }
            ProxyService.start(context)
        }
    }
}
