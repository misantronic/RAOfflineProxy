package com.raofflineproxy.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.raofflineproxy.PrefsConstants
import com.raofflineproxy.R
import com.raofflineproxy.proxyBase
import com.raofflineproxy.proxyPort

internal const val UI_EMUCOREX_PACKAGE = "com.sbro.emucorex"

internal val UI_EMUCOREX_PACKAGE_CANDIDATES = listOf(
    UI_EMUCOREX_PACKAGE
)

private const val EMUCOREX_RECEIVER_CLASS = "com.sbro.emucorex.core.utils.RetroAchievementsHostOverrideReceiver"
private const val EMUCOREX_SET_ACTION_SUFFIX = ".action.SET_RETROACHIEVEMENTS_HOST_OVERRIDE"
private const val EMUCOREX_CLEAR_ACTION_SUFFIX = ".action.CLEAR_RETROACHIEVEMENTS_HOST_OVERRIDE"
private const val EMUCOREX_HOST_OVERRIDE_EXTRA = "host"

data class EmuCoreXPatchResult(
    val success: Boolean,
    val message: String,
    val skippedNotInstalled: Boolean = false
)

fun patchEmuCoreXCfg(context: Context): EmuCoreXPatchResult {
    val packageName = resolveInstalledPackage(context, UI_EMUCOREX_PACKAGE_CANDIDATES)
        ?: return EmuCoreXPatchResult(
            success = true,
            message = "EmuCoreX not installed.",
            skippedNotInstalled = true
        )
    if (!supportsEmuCoreXBroadcastOverride(context, packageName)) {
        return EmuCoreXPatchResult(success = false, message = context.getString(R.string.emucorex_patch_error_unavailable))
    }

    sendEmuCoreXBroadcast(
        context = context,
        packageName = packageName,
        action = packageName + EMUCOREX_SET_ACTION_SUFFIX,
        hostOverride = proxyBase(proxyPort(context))
    )
    return EmuCoreXPatchResult(success = true, message = context.getString(R.string.emucorex_patch_success))
}

fun revertEmuCoreXCfg(context: Context): EmuCoreXPatchResult {
    val packageName = resolveInstalledPackage(context, UI_EMUCOREX_PACKAGE_CANDIDATES)
        ?: return EmuCoreXPatchResult(
            success = true,
            message = "EmuCoreX not installed.",
            skippedNotInstalled = true
        )
    if (!supportsEmuCoreXBroadcastOverride(context, packageName)) {
        return EmuCoreXPatchResult(success = false, message = context.getString(R.string.emucorex_revert_error_unavailable))
    }

    sendEmuCoreXBroadcast(
        context = context,
        packageName = packageName,
        action = packageName + EMUCOREX_CLEAR_ACTION_SUFFIX
    )
    return EmuCoreXPatchResult(success = true, message = context.getString(R.string.emucorex_revert_success))
}

fun checkIsEmuCoreXPatched(context: Context): Boolean =
    context.getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(PrefsConstants.KEY_EMUCOREX_PATCHED_THIS_RUN, false)

internal fun supportsEmuCoreXBroadcastOverride(context: Context, packageName: String): Boolean {
    val receiverComponent = ComponentName(packageName, EMUCOREX_RECEIVER_CLASS)
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getReceiverInfo(
                receiverComponent,
                PackageManager.ComponentInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getReceiverInfo(receiverComponent, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}

private fun sendEmuCoreXBroadcast(
    context: Context,
    packageName: String,
    action: String,
    hostOverride: String? = null
) {
    val intent = Intent(action)
        .setPackage(packageName)
        .setComponent(ComponentName(packageName, EMUCOREX_RECEIVER_CLASS))
        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)

    if (hostOverride != null) {
        intent.putExtra(EMUCOREX_HOST_OVERRIDE_EXTRA, hostOverride)
    }

    context.sendBroadcast(intent)
}
