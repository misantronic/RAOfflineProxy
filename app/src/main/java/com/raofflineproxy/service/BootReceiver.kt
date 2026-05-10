package com.raofflineproxy.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.raofflineproxy.PrefsConstants
import com.raofflineproxy.ui.loadEmulatorSupport
import com.raofflineproxy.ui.patchDolphinCfg
import com.raofflineproxy.ui.patchRetroArchCfg

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PrefsConstants.KEY_AUTOSTART_PROXY, false)) return

        val emulatorSupport = loadEmulatorSupport(context)
        if (!emulatorSupport.hasAnyEnabled) return

        val treeUri = PrefsConstants.loadSafUri(context)
        val dolphinTreeUri = PrefsConstants.loadDolphinSafUri(context)
        prefs.edit { remove(PrefsConstants.KEY_SKIP_NEXT_CFG_REVERT) }

        val result = if (emulatorSupport.retroArchEnabled) patchRetroArchCfg(context, treeUri)
        else com.raofflineproxy.ui.PatchResult(success = true, message = "RetroArch disabled.")

        if (result.success) {
            prefs.edit {
                if (emulatorSupport.retroArchEnabled) {
                    putBoolean(PrefsConstants.KEY_RETROARCH_HARDCORE_WAS_ENABLED, result.hardcoreWasEnabled)
                    putBoolean(PrefsConstants.KEY_RETROARCH_PATCHED_THIS_RUN, true)
                } else {
                    remove(PrefsConstants.KEY_RETROARCH_PATCHED_THIS_RUN)
                }
            }

            val dolphinResult = if (emulatorSupport.dolphinEnabled) patchDolphinCfg(context, dolphinTreeUri)
            else com.raofflineproxy.ui.DolphinPatchResult(success = true, message = "Dolphin disabled.", skippedNotInstalled = true)

            if (dolphinResult.success && !dolphinResult.skippedNotInstalled) {
                prefs.edit {
                    putBoolean(
                        PrefsConstants.KEY_DOLPHIN_HARDCORE_WAS_ENABLED,
                        dolphinResult.hardcoreWasEnabled
                    )
                    putBoolean(PrefsConstants.KEY_DOLPHIN_PATCHED_THIS_RUN, true)
                }
            } else if (!emulatorSupport.dolphinEnabled) {
                prefs.edit { remove(PrefsConstants.KEY_DOLPHIN_PATCHED_THIS_RUN) }
            }
            ProxyService.start(context)
        }
    }
}
