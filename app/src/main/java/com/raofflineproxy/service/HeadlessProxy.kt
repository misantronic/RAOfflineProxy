package com.raofflineproxy.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.raofflineproxy.PrefsConstants
import com.raofflineproxy.isLoopbackPortAvailable
import com.raofflineproxy.data.AppDatabase
import com.raofflineproxy.proxy.loadLoginCredentials
import com.raofflineproxy.ui.BroadcastPatchResult
import com.raofflineproxy.ui.ConfigPatchResult
import com.raofflineproxy.ui.Emulator
import com.raofflineproxy.ui.EmulatorSupport
import com.raofflineproxy.ui.broadcastDisabledResult
import com.raofflineproxy.ui.broadcastNotPatchedResult
import com.raofflineproxy.ui.clearShizukuHardcoreWasEnabled
import com.raofflineproxy.ui.configDisabledResult
import com.raofflineproxy.ui.configNotPatchedResult
import com.raofflineproxy.ui.executeShizukuManualPatch
import com.raofflineproxy.ui.loadConfigSafUri
import com.raofflineproxy.ui.loadEmulatorSupport
import com.raofflineproxy.ui.loadShizukuHardcoreWasEnabled
import com.raofflineproxy.ui.patchBroadcastCfg
import com.raofflineproxy.ui.patchConfigCfg
import com.raofflineproxy.ui.requireConfigOverride
import com.raofflineproxy.ui.revertBroadcastCfg
import com.raofflineproxy.ui.revertConfigCfg
import com.raofflineproxy.ui.saveShizukuHardcoreWasEnabled
import kotlinx.coroutines.runBlocking

private const val TAG = "RAProxy/HeadlessProxy"

internal enum class HeadlessStartResult { Started, NoEmulatorEnabled, PortUnavailable, PatchFailed }

/** Starts and stops the proxy without the UI, for boot and for other apps: patches the emulators
 *  the same way the UI does and rolls the patches back when a start fails. */
internal object HeadlessProxy {

    fun start(context: Context): HeadlessStartResult {
        val emulatorSupport = loadEmulatorSupport(context)
        if (!emulatorSupport.canStartProxy) return HeadlessStartResult.NoEmulatorEnabled
        if (!isLoopbackPortAvailable(PrefsConstants.loadProxyPort(context))) return HeadlessStartResult.PortUnavailable

        val prefs = context.getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val started = if (PrefsConstants.loadManualEmulatorPatchingEnabled(context)) {
            startWithManualPatching(context, prefs, emulatorSupport)
        } else {
            startWithAutomaticPatching(context, prefs, emulatorSupport)
        }
        return if (started) HeadlessStartResult.Started else HeadlessStartResult.PatchFailed
    }

    /** Stopping the service reverts what it patched in onDestroy. Shizuku patches aren't tracked
     *  per run, so they're reverted here first, as the UI does. */
    fun stop(context: Context) {
        revertShizukuManualPatch(context)
        val wasRunning = ProxyService.isRunning(context)
        ProxyService.stop(context)
        if (!wasRunning) revertPatchedEmulatorConfigs(context)
    }

    private fun usesShizukuManualPatching(context: Context, emulatorSupport: EmulatorSupport): Boolean =
        PrefsConstants.loadManualEmulatorPatchingEnabled(context) &&
            PrefsConstants.loadShizukuManualPatchingEnabled(context) &&
            emulatorSupport.hasAnyShizukuManagedEnabled

    private fun revertShizukuManualPatch(context: Context) {
        val emulatorSupport = loadEmulatorSupport(context)
        if (!usesShizukuManualPatching(context, emulatorSupport)) return
        val result = runBlocking {
            executeShizukuManualPatch(
                context = context,
                support = emulatorSupport,
                action = "revert",
                restoreHardcore = loadShizukuHardcoreWasEnabled(context)
            )
        }
        if (result.success) {
            clearShizukuHardcoreWasEnabled(context)
        } else {
            Log.w(TAG, "Shizuku revert failed: ${result.message}")
        }
    }

    private fun startWithManualPatching(
        context: Context,
        prefs: SharedPreferences,
        emulatorSupport: EmulatorSupport
    ): Boolean {
        val shizukuResult = if (usesShizukuManualPatching(context, emulatorSupport)) {
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
            return true
        }
        return false
    }

    private fun startWithAutomaticPatching(
        context: Context,
        prefs: SharedPreferences,
        emulatorSupport: EmulatorSupport
    ): Boolean {
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
            return true
        }

        rollbackAutomaticPatching(context, prefs, patchedEmulators, configResults)
        return false
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
                    remove(emulator.hardcoreWasEnabledPrefsKey)
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
                putBoolean(emulator.hardcoreWasEnabledPrefsKey, result.hardcoreWasEnabled)
                putBoolean(emulator.patchedThisRunPrefsKey, true)
            } else {
                remove(emulator.hardcoreWasEnabledPrefsKey)
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

/** Reverts every emulator patched during this run, unless the UI already did. */
internal fun revertPatchedEmulatorConfigs(context: Context) {
    val prefs = context.getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE)
    if (prefs.getBoolean(PrefsConstants.KEY_SKIP_NEXT_CFG_REVERT, false)) {
        prefs.edit { remove(PrefsConstants.KEY_SKIP_NEXT_CFG_REVERT) }
        Log.i(TAG, "Skipping RetroArch cfg revert; UI already handled it")
        return
    }

    val configResults = Emulator.SHIZUKU_MANAGED.associateWith { emulator ->
        val config = requireConfigOverride(emulator)
        if (prefs.getBoolean(emulator.patchedThisRunPrefsKey, false)) {
            revertConfigCfg(
                context = context,
                emulator = emulator,
                treeUri = loadConfigSafUri(context, emulator),
                restoreHardcore = prefs.getBoolean(emulator.hardcoreWasEnabledPrefsKey, false)
            )
        } else {
            configNotPatchedResult(emulator)
        }
    }
    val broadcastResults = Emulator.BROADCAST_MANAGED.associateWith { emulator ->
        if (prefs.getBoolean(emulator.patchedThisRunPrefsKey, false)) {
            revertBroadcastCfg(context, emulator)
        } else {
            broadcastNotPatchedResult(emulator)
        }
    }

    configResults.forEach { (emulator, result) ->
        if (!result.success || result.copyBackPath != null) return@forEach
        prefs.edit {
            remove(emulator.hardcoreWasEnabledPrefsKey)
            remove(emulator.patchedThisRunPrefsKey)
        }
        Log.i(TAG, "${emulator.displayName} config reverted during proxy shutdown")
    }
    broadcastResults.forEach { (emulator, result) ->
        if (!result.success) return@forEach
        prefs.edit { remove(emulator.patchedThisRunPrefsKey) }
        Log.i(TAG, "${emulator.displayName} host override reverted during proxy shutdown")
    }

    val failedConfig = configResults.values.firstOrNull { !it.success || it.copyBackPath != null }
    val failedBroadcast = broadcastResults.values.firstOrNull { !it.success }
    val reason = when {
        failedConfig != null -> failedConfig.copyBackPath
            ?.let { "${failedConfig.message} copyBackPath=$it" }
            ?: failedConfig.message
        failedBroadcast != null -> failedBroadcast.message
        else -> return
    }
    Log.w(TAG, "Failed to revert emulator config during proxy shutdown: $reason")
}
