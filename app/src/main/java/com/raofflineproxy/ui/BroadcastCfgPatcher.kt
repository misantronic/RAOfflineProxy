package com.raofflineproxy.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.raofflineproxy.PrefsConstants

private const val SET_ACTION_SUFFIX = ".action.SET_RETROACHIEVEMENTS_HOST_OVERRIDE"
private const val CLEAR_ACTION_SUFFIX = ".action.CLEAR_RETROACHIEVEMENTS_HOST_OVERRIDE"
private const val HOST_OVERRIDE_EXTRA = "host"

data class BroadcastPatchResult(
    val success: Boolean,
    val message: String,
    val skippedNotInstalled: Boolean = false
)

fun patchBroadcastCfg(context: Context, emulator: Emulator): BroadcastPatchResult =
    sendHostOverride(context, emulator, clear = false)

fun revertBroadcastCfg(context: Context, emulator: Emulator): BroadcastPatchResult =
    sendHostOverride(context, emulator, clear = true)

fun broadcastDisabledResult(emulator: Emulator): BroadcastPatchResult = BroadcastPatchResult(
    success = true,
    message = "${emulator.displayName} disabled.",
    skippedNotInstalled = true
)

fun broadcastNotPatchedResult(emulator: Emulator): BroadcastPatchResult = BroadcastPatchResult(
    success = true,
    message = "${emulator.displayName} not patched this run.",
    skippedNotInstalled = true
)

fun checkIsBroadcastPatched(context: Context, emulator: Emulator): Boolean =
    context.getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(emulator.patchedThisRunPrefsKey, false)

internal fun supportsBroadcastOverride(context: Context, emulator: Emulator, packageName: String): Boolean =
    resolveReceiverClass(context, emulator, packageName) != null

private fun resolveReceiverClass(context: Context, emulator: Emulator, packageName: String): String? {
    val override = emulator.broadcastOverride ?: return null
    return override.receiverClassesFor(packageName).firstOrNull { receiverClass ->
        receiverExists(context, ComponentName(packageName, receiverClass))
    }
}

private fun receiverExists(context: Context, component: ComponentName): Boolean =
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getReceiverInfo(component, PackageManager.ComponentInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getReceiverInfo(component, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

private fun sendHostOverride(
    context: Context,
    emulator: Emulator,
    clear: Boolean
): BroadcastPatchResult {
    val override = requireNotNull(emulator.broadcastOverride) {
        "${emulator.displayName} is not patched via host-override broadcast"
    }
    val packageName = resolveInstalledPackage(context, emulator.packageCandidates)
        ?: return BroadcastPatchResult(
            success = true,
            message = "${emulator.displayName} not installed.",
            skippedNotInstalled = true
        )
    val receiverClass = resolveReceiverClass(context, emulator, packageName)
    if (receiverClass == null) {
        return BroadcastPatchResult(
            success = false,
            message = context.getString(if (clear) override.revertErrorRes else override.patchErrorRes)
        )
    }

    val intent = Intent(packageName + if (clear) CLEAR_ACTION_SUFFIX else SET_ACTION_SUFFIX)
        .setPackage(packageName)
        .setComponent(ComponentName(packageName, receiverClass))
        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
    if (!clear) {
        intent.putExtra(HOST_OVERRIDE_EXTRA, override.hostValue(context))
    }
    context.sendBroadcast(intent)

    return BroadcastPatchResult(
        success = true,
        message = context.getString(if (clear) override.revertSuccessRes else override.patchSuccessRes)
    )
}
