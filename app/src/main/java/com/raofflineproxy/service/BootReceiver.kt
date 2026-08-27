package com.raofflineproxy.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.edit
import com.raofflineproxy.PrefsConstants
import com.raofflineproxy.isLoopbackPortAvailable
import com.raofflineproxy.data.AppDatabase
import com.raofflineproxy.proxy.loadLoginCredentials
import com.raofflineproxy.ui.BroadcastPatchResult
import com.raofflineproxy.ui.broadcastDisabledResult
import com.raofflineproxy.ui.ConfigPatchResult
import com.raofflineproxy.ui.Emulator
import com.raofflineproxy.ui.EmulatorSupport
import com.raofflineproxy.ui.configDisabledResult
import com.raofflineproxy.ui.executeShizukuManualPatch
import com.raofflineproxy.ui.loadConfigSafUri
import com.raofflineproxy.ui.loadEmulatorSupport
import com.raofflineproxy.ui.patchBroadcastCfg
import com.raofflineproxy.ui.patchConfigCfg
import com.raofflineproxy.ui.requireConfigOverride
import com.raofflineproxy.ui.revertBroadcastCfg
import com.raofflineproxy.ui.revertConfigCfg
import com.raofflineproxy.ui.saveShizukuHardcoreWasEnabled
import kotlinx.coroutines.runBlocking

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val shouldRestartProxy = prefs.getBoolean(PrefsConstants.KEY_AUTOSTART_PROXY, false) || ProxyService.shouldKeepRunning(context)
        if (!shouldRestartProxy) return

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
        prefs: SharedPreferences,
        emulatorSupport: EmulatorSupport
    ) {
        val shizukuResult = if (emulatorSupport.hasAnyShizukuManagedEnabled) {
            runBlocking {
                executeShizukuManualPatch(context, emulatorSupport, "patch")
            }.also {
                if (it.success) saveShizukuHardcoreWasEnabled(context, it.hardcoreWasEnabled)
            }
        } else {
            null
        }

        val broadcastResults = patchBroadcastEmulators(context, prefs, emulatorSupport)

        if ((shizukuResult == null || shizukuResult.success) && broadcastResults.values.all { it.success }) {
            ProxyService.start(context)
        }
    }

    private fun startWithAutomaticPatching(
        context: Context,
        prefs: SharedPreferences,
        emulatorSupport: EmulatorSupport
    ) {
        prefs.edit { remove(PrefsConstants.KEY_SKIP_NEXT_CFG_REVERT) }

        val configResults = Emulator.SHIZUKU_MANAGED.associateWith { emulator ->
            patchConfigAndPersist(context, prefs, emulator, emulatorSupport.isEnabled(emulator))
        }
        val broadcastResults = patchBroadcastEmulators(context, prefs, emulatorSupport)

        val patchedEmulators = emulatorSupport.enabled.filter { emulator ->
            configResults[emulator]?.let { isConfigReadyForAutostart(true, it) }
                ?: broadcastResults.getValue(emulator).success
        }

        if (patchedEmulators.size == emulatorSupport.enabled.size) {
            ProxyService.start(context)
            return
        }

        rollbackAutomaticPatching(context, prefs, patchedEmulators, configResults)
    }

    private fun rollbackAutomaticPatching(
        context: Context,
        prefs: SharedPreferences,
        patchedEmulators: List<Emulator>,
        configResults: Map<Emulator, ConfigPatchResult>
    ) {
        patchedEmulators.forEach { emulator ->
            val config = emulator.configOverride
            if (config == null) {
                if (revertBroadcastCfg(context, emulator).success) {
                    prefs.edit { remove(emulator.patchedThisRunPrefsKey) }
                }
                return@forEach
            }

            val result = revertConfigCfg(
                context = context,
                emulator = emulator,
                treeUri = loadConfigSafUri(context, emulator),
                restoreHardcore = configResults.getValue(emulator).hardcoreWasEnabled
            )
            if (result.success && result.copyBackPath == null) {
                prefs.edit {
                    remove(config.hardcoreWasEnabledPrefsKey)
                    remove(emulator.patchedThisRunPrefsKey)
                }
            }
        }
    }

    private fun isConfigReadyForAutostart(enabled: Boolean, result: ConfigPatchResult): Boolean =
        !enabled || (result.success && !result.needsSafGrant && !result.invalidSafGrant && result.copyBackPath == null)

    private fun patchConfigAndPersist(
        context: Context,
        prefs: SharedPreferences,
        emulator: Emulator,
        enabled: Boolean
    ): ConfigPatchResult {
        val config = requireConfigOverride(emulator)
        val credentials = if (enabled && config.needsCredentials) {
            runBlocking { loadLoginCredentials(AppDatabase.getInstance(context)) }
        } else {
            null
        }

        val result = if (enabled) {
            patchConfigCfg(context, emulator, loadConfigSafUri(context, emulator), credentials)
        } else {
            configDisabledResult(emulator)
        }

        prefs.edit {
            if (isConfigReadyForAutostart(enabled, result) && !result.skippedNotInstalled) {
                putBoolean(config.hardcoreWasEnabledPrefsKey, result.hardcoreWasEnabled)
                putBoolean(emulator.patchedThisRunPrefsKey, true)
            } else {
                remove(config.hardcoreWasEnabledPrefsKey)
                remove(emulator.patchedThisRunPrefsKey)
            }
        }

        return result
    }

    private fun patchBroadcastEmulators(
        context: Context,
        prefs: SharedPreferences,
        emulatorSupport: EmulatorSupport
    ): Map<Emulator, BroadcastPatchResult> = Emulator.BROADCAST_MANAGED.associateWith { emulator ->
        val result = if (emulatorSupport.isEnabled(emulator)) {
            patchBroadcastCfg(context, emulator)
        } else {
            broadcastDisabledResult(emulator)
        }

        if (result.success && !result.skippedNotInstalled) {
            prefs.edit { putBoolean(emulator.patchedThisRunPrefsKey, true) }
        } else {
            prefs.edit { remove(emulator.patchedThisRunPrefsKey) }
        }

        result
    }
}
