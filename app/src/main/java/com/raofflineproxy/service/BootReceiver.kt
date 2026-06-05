package com.raofflineproxy.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.raofflineproxy.PrefsConstants
import com.raofflineproxy.isLoopbackPortAvailable
import com.raofflineproxy.data.AppDatabase
import com.raofflineproxy.proxy.loadLoginCredentials
import com.raofflineproxy.ui.executeShizukuManualPatch
import com.raofflineproxy.ui.loadEmulatorSupport
import com.raofflineproxy.ui.patchArmsx2Cfg
import com.raofflineproxy.ui.patchDolphinCfg
import com.raofflineproxy.ui.patchPpssppCfg
import com.raofflineproxy.ui.patchRetroArchCfg
import com.raofflineproxy.ui.EmulatorSupport
import com.raofflineproxy.ui.revertArmsx2Cfg
import com.raofflineproxy.ui.revertDolphinCfg
import com.raofflineproxy.ui.revertPpssppCfg
import com.raofflineproxy.ui.revertRetroArchCfg
import kotlinx.coroutines.runBlocking

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PrefsConstants.KEY_AUTOSTART_PROXY, false)) return

        val emulatorSupport = loadEmulatorSupport(context)
        if (!emulatorSupport.hasAnyEnabled) return

        val preferredPort = PrefsConstants.loadProxyPort(context)
        if (!isLoopbackPortAvailable(preferredPort)) return

        if (PrefsConstants.loadManualEmulatorPatchingEnabled(context)) {
            startWithManualPatching(context, prefs, emulatorSupport)
            return
        }

        startWithAutomaticPatching(context, prefs, emulatorSupport)
    }

    private fun startWithManualPatching(
        context: Context,
        prefs: android.content.SharedPreferences,
        emulatorSupport: EmulatorSupport
    ) {
        val shizukuResult = if (emulatorSupport.hasAnyShizukuManagedEnabled) {
            runBlocking {
                executeShizukuManualPatch(context, emulatorSupport, "patch")
            }
        } else {
            null
        }

        val armsx2Result = patchArmsx2AndPersist(context, prefs, emulatorSupport.armsx2Enabled)

        if ((shizukuResult == null || shizukuResult.success) && armsx2Result.success) {
            ProxyService.start(context)
        }
    }

    private fun startWithAutomaticPatching(
        context: Context,
        prefs: android.content.SharedPreferences,
        emulatorSupport: EmulatorSupport
    ) {
        val treeUri = PrefsConstants.loadSafUri(context)
        val dolphinTreeUri = PrefsConstants.loadDolphinSafUri(context)
        val ppssppTreeUri = PrefsConstants.loadPpssppSafUri(context)
        prefs.edit { remove(PrefsConstants.KEY_SKIP_NEXT_CFG_REVERT) }

        val retroArchResult = patchRetroArchAndPersist(context, prefs, emulatorSupport.retroArchEnabled, treeUri)
        val dolphinResult = patchDolphinAndPersist(context, prefs, emulatorSupport.dolphinEnabled, dolphinTreeUri)
        val ppssppResult = patchPpssppAndPersist(context, prefs, emulatorSupport.ppssppEnabled, ppssppTreeUri)
        val armsx2Result = patchArmsx2AndPersist(context, prefs, emulatorSupport.armsx2Enabled)

        val retroArchReady = isRetroArchReadyForAutostart(emulatorSupport.retroArchEnabled, retroArchResult)
        val dolphinReady = isDolphinReadyForAutostart(emulatorSupport.dolphinEnabled, dolphinResult)
        val ppssppReady = isPpssppReadyForAutostart(emulatorSupport.ppssppEnabled, ppssppResult)
        val armsx2Ready = isArmsx2ReadyForAutostart(emulatorSupport.armsx2Enabled, armsx2Result)

        if (retroArchReady && dolphinReady && ppssppReady && armsx2Ready) {
            ProxyService.start(context)
            return
        }

        rollbackAutomaticPatchingIfNeeded(
            context = context,
            prefs = prefs,
            retroArchPatched = emulatorSupport.retroArchEnabled && retroArchReady,
            retroArchTreeUri = treeUri,
            retroArchHardcoreWasEnabled = retroArchResult.hardcoreWasEnabled,
            dolphinPatched = emulatorSupport.dolphinEnabled && dolphinReady,
            dolphinTreeUri = dolphinTreeUri,
            dolphinHardcoreWasEnabled = dolphinResult.hardcoreWasEnabled,
            ppssppPatched = emulatorSupport.ppssppEnabled && ppssppReady,
            ppssppTreeUri = ppssppTreeUri,
            ppssppHardcoreWasEnabled = ppssppResult.hardcoreWasEnabled,
            armsx2Patched = emulatorSupport.armsx2Enabled && armsx2Ready
        )
    }

    private fun rollbackAutomaticPatchingIfNeeded(
        context: Context,
        prefs: android.content.SharedPreferences,
        retroArchPatched: Boolean,
        retroArchTreeUri: android.net.Uri?,
        retroArchHardcoreWasEnabled: Boolean,
        dolphinPatched: Boolean,
        dolphinTreeUri: android.net.Uri?,
        dolphinHardcoreWasEnabled: Boolean,
        ppssppPatched: Boolean,
        ppssppTreeUri: android.net.Uri?,
        ppssppHardcoreWasEnabled: Boolean,
        armsx2Patched: Boolean
    ) {
        if (retroArchPatched) {
            val result = revertRetroArchCfg(context, retroArchTreeUri, retroArchHardcoreWasEnabled)
            if (result.success && result.copyBackPath == null) {
                prefs.edit {
                    remove(PrefsConstants.KEY_RETROARCH_HARDCORE_WAS_ENABLED)
                    remove(PrefsConstants.KEY_RETROARCH_PATCHED_THIS_RUN)
                }
            }
        }

        if (dolphinPatched) {
            val result = revertDolphinCfg(context, dolphinTreeUri, dolphinHardcoreWasEnabled)
            if (result.success && result.copyBackPath == null) {
                prefs.edit {
                    remove(PrefsConstants.KEY_DOLPHIN_HARDCORE_WAS_ENABLED)
                    remove(PrefsConstants.KEY_DOLPHIN_PATCHED_THIS_RUN)
                }
            }
        }

        if (ppssppPatched) {
            val result = revertPpssppCfg(context, ppssppTreeUri, ppssppHardcoreWasEnabled)
            if (result.success && result.copyBackPath == null) {
                prefs.edit {
                    remove(PrefsConstants.KEY_PPSSPP_HARDCORE_WAS_ENABLED)
                    remove(PrefsConstants.KEY_PPSSPP_PATCHED_THIS_RUN)
                }
            }
        }

        if (armsx2Patched) {
            val result = revertArmsx2Cfg(context)
            if (result.success) {
                prefs.edit { remove(PrefsConstants.KEY_ARMSX2_PATCHED_THIS_RUN) }
            }
        }
    }

    private fun isRetroArchReadyForAutostart(
        enabled: Boolean,
        result: com.raofflineproxy.ui.PatchResult
    ): Boolean =
        !enabled || (result.success && !result.needsSafGrant && !result.invalidSafGrant && result.copyBackPath == null)

    private fun isDolphinReadyForAutostart(
        enabled: Boolean,
        result: com.raofflineproxy.ui.DolphinPatchResult
    ): Boolean =
        !enabled || (result.success && !result.needsSafGrant && !result.invalidSafGrant && result.copyBackPath == null)

    private fun isPpssppReadyForAutostart(
        enabled: Boolean,
        result: com.raofflineproxy.ui.PpssppPatchResult
    ): Boolean =
        !enabled || (result.success && !result.needsSafGrant && !result.invalidSafGrant && result.copyBackPath == null)

    private fun isArmsx2ReadyForAutostart(
        enabled: Boolean,
        result: com.raofflineproxy.ui.Armsx2PatchResult
    ): Boolean =
        !enabled || result.success

    private fun patchRetroArchAndPersist(
        context: Context,
        prefs: android.content.SharedPreferences,
        enabled: Boolean,
        treeUri: android.net.Uri?
    ): com.raofflineproxy.ui.PatchResult {
        val result = if (enabled) {
            patchRetroArchCfg(context, treeUri)
        } else {
            com.raofflineproxy.ui.PatchResult(success = true, message = "RetroArch disabled.")
        }

        prefs.edit {
            if (isRetroArchReadyForAutostart(enabled, result)) {
                putBoolean(PrefsConstants.KEY_RETROARCH_HARDCORE_WAS_ENABLED, result.hardcoreWasEnabled)
                putBoolean(PrefsConstants.KEY_RETROARCH_PATCHED_THIS_RUN, true)
            } else {
                remove(PrefsConstants.KEY_RETROARCH_HARDCORE_WAS_ENABLED)
                remove(PrefsConstants.KEY_RETROARCH_PATCHED_THIS_RUN)
            }
        }

        return result
    }

    private fun patchDolphinAndPersist(
        context: Context,
        prefs: android.content.SharedPreferences,
        enabled: Boolean,
        treeUri: android.net.Uri?
    ): com.raofflineproxy.ui.DolphinPatchResult {
        val storedCredentials = if (enabled) {
            runBlocking { loadLoginCredentials(AppDatabase.getInstance(context)) }
        } else {
            null
        }

        val result = if (enabled) {
            patchDolphinCfg(context, treeUri, storedCredentials)
        } else {
            com.raofflineproxy.ui.DolphinPatchResult(success = true, message = "Dolphin disabled.", skippedNotInstalled = true)
        }

        prefs.edit {
            if (isDolphinReadyForAutostart(enabled, result) && !result.skippedNotInstalled) {
                putBoolean(PrefsConstants.KEY_DOLPHIN_HARDCORE_WAS_ENABLED, result.hardcoreWasEnabled)
                putBoolean(PrefsConstants.KEY_DOLPHIN_PATCHED_THIS_RUN, true)
            } else {
                remove(PrefsConstants.KEY_DOLPHIN_HARDCORE_WAS_ENABLED)
                remove(PrefsConstants.KEY_DOLPHIN_PATCHED_THIS_RUN)
            }
        }

        return result
    }

    private fun patchPpssppAndPersist(
        context: Context,
        prefs: android.content.SharedPreferences,
        enabled: Boolean,
        treeUri: android.net.Uri?
    ): com.raofflineproxy.ui.PpssppPatchResult {
        val result = if (enabled) {
            patchPpssppCfg(context, treeUri)
        } else {
            com.raofflineproxy.ui.PpssppPatchResult(success = true, message = "PPSSPP disabled.", skippedNotInstalled = true)
        }

        prefs.edit {
            if (isPpssppReadyForAutostart(enabled, result) && !result.skippedNotInstalled) {
                putBoolean(PrefsConstants.KEY_PPSSPP_HARDCORE_WAS_ENABLED, result.hardcoreWasEnabled)
                putBoolean(PrefsConstants.KEY_PPSSPP_PATCHED_THIS_RUN, true)
            } else {
                remove(PrefsConstants.KEY_PPSSPP_HARDCORE_WAS_ENABLED)
                remove(PrefsConstants.KEY_PPSSPP_PATCHED_THIS_RUN)
            }
        }

        return result
    }

    private fun patchArmsx2AndPersist(
        context: Context,
        prefs: android.content.SharedPreferences,
        enabled: Boolean
    ): com.raofflineproxy.ui.Armsx2PatchResult {
        val result = if (enabled) {
            patchArmsx2Cfg(context)
        } else {
            com.raofflineproxy.ui.Armsx2PatchResult(success = true, message = "ARMSX2 disabled.", skippedNotInstalled = true)
        }

        if (isArmsx2ReadyForAutostart(enabled, result) && !result.skippedNotInstalled) {
            prefs.edit { putBoolean(PrefsConstants.KEY_ARMSX2_PATCHED_THIS_RUN, true) }
        } else {
            prefs.edit { remove(PrefsConstants.KEY_ARMSX2_PATCHED_THIS_RUN) }
        }

        return result
    }
}
