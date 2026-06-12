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

internal const val UI_FLYCAST_PACKAGE = "com.flycast.emulator"

internal val UI_FLYCAST_PACKAGE_CANDIDATES = listOf(
    UI_FLYCAST_PACKAGE
)

private const val FLYCAST_RECEIVER_CLASS = "com.flycast.emulator.RetroAchievementsHostOverrideReceiver"
private const val FLYCAST_SET_ACTION_SUFFIX = ".action.SET_RETROACHIEVEMENTS_HOST_OVERRIDE"
private const val FLYCAST_CLEAR_ACTION_SUFFIX = ".action.CLEAR_RETROACHIEVEMENTS_HOST_OVERRIDE"
private const val FLYCAST_HOST_OVERRIDE_EXTRA = "host"

data class FlycastPatchResult(
    val success: Boolean,
    val message: String,
    val skippedNotInstalled: Boolean = false
)

fun patchFlycastCfg(context: Context): FlycastPatchResult {
    val packageName = resolveInstalledPackage(context, UI_FLYCAST_PACKAGE_CANDIDATES)
        ?: return FlycastPatchResult(
            success = true,
            message = "Flycast not installed.",
            skippedNotInstalled = true
        )
    if (!supportsFlycastBroadcastOverride(context, packageName)) {
        return FlycastPatchResult(success = false, message = context.getString(R.string.flycast_patch_error_unavailable))
    }

    sendFlycastBroadcast(
        context = context,
        packageName = packageName,
        action = packageName + FLYCAST_SET_ACTION_SUFFIX,
        hostOverride = proxyBase(proxyPort(context))
    )
    return FlycastPatchResult(success = true, message = context.getString(R.string.flycast_patch_success))
}

fun revertFlycastCfg(context: Context): FlycastPatchResult {
    val packageName = resolveInstalledPackage(context, UI_FLYCAST_PACKAGE_CANDIDATES)
        ?: return FlycastPatchResult(
            success = true,
            message = "Flycast not installed.",
            skippedNotInstalled = true
        )
    if (!supportsFlycastBroadcastOverride(context, packageName)) {
        return FlycastPatchResult(success = false, message = context.getString(R.string.flycast_revert_error_unavailable))
    }

    sendFlycastBroadcast(
        context = context,
        packageName = packageName,
        action = packageName + FLYCAST_CLEAR_ACTION_SUFFIX
    )
    return FlycastPatchResult(success = true, message = context.getString(R.string.flycast_revert_success))
}

fun checkIsFlycastPatched(context: Context): Boolean =
    context.getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(PrefsConstants.KEY_FLYCAST_PATCHED_THIS_RUN, false)

internal fun supportsFlycastBroadcastOverride(context: Context, packageName: String): Boolean {
    val receiverComponent = ComponentName(packageName, FLYCAST_RECEIVER_CLASS)
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

private fun sendFlycastBroadcast(
    context: Context,
    packageName: String,
    action: String,
    hostOverride: String? = null
) {
    val intent = Intent(action)
        .setPackage(packageName)
        .setComponent(ComponentName(packageName, FLYCAST_RECEIVER_CLASS))
        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)

    if (hostOverride != null) {
        intent.putExtra(FLYCAST_HOST_OVERRIDE_EXTRA, hostOverride)
    }

    context.sendBroadcast(intent)
}
