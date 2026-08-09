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

internal const val UI_WATERMELONDS_PACKAGE = "me.magnum.melondualds"

internal val UI_WATERMELONDS_PACKAGE_CANDIDATES = listOf(
    UI_WATERMELONDS_PACKAGE
)

private const val WATERMELONDS_RECEIVER_CLASS = "me.magnum.melondualds.RetroAchievementsHostOverrideReceiver"
private const val WATERMELONDS_SET_ACTION_SUFFIX = ".action.SET_RETROACHIEVEMENTS_HOST_OVERRIDE"
private const val WATERMELONDS_CLEAR_ACTION_SUFFIX = ".action.CLEAR_RETROACHIEVEMENTS_HOST_OVERRIDE"
private const val WATERMELONDS_HOST_OVERRIDE_EXTRA = "host"

data class WatermelonDsPatchResult(
    val success: Boolean,
    val message: String,
    val skippedNotInstalled: Boolean = false
)

fun patchWatermelonDsCfg(context: Context): WatermelonDsPatchResult {
    val packageName = resolveInstalledPackage(context, UI_WATERMELONDS_PACKAGE_CANDIDATES)
        ?: return WatermelonDsPatchResult(
            success = true,
            message = "WatermelonDS not installed.",
            skippedNotInstalled = true
        )
    if (!supportsWatermelonDsBroadcastOverride(context, packageName)) {
        return WatermelonDsPatchResult(success = false, message = context.getString(R.string.watermelonds_patch_error_unavailable))
    }

    sendWatermelonDsBroadcast(
        context = context,
        packageName = packageName,
        action = packageName + WATERMELONDS_SET_ACTION_SUFFIX,
        hostOverride = proxyBase(proxyPort(context))
    )
    return WatermelonDsPatchResult(success = true, message = context.getString(R.string.watermelonds_patch_success))
}

fun revertWatermelonDsCfg(context: Context): WatermelonDsPatchResult {
    val packageName = resolveInstalledPackage(context, UI_WATERMELONDS_PACKAGE_CANDIDATES)
        ?: return WatermelonDsPatchResult(
            success = true,
            message = "WatermelonDS not installed.",
            skippedNotInstalled = true
        )
    if (!supportsWatermelonDsBroadcastOverride(context, packageName)) {
        return WatermelonDsPatchResult(success = false, message = context.getString(R.string.watermelonds_revert_error_unavailable))
    }

    sendWatermelonDsBroadcast(
        context = context,
        packageName = packageName,
        action = packageName + WATERMELONDS_CLEAR_ACTION_SUFFIX
    )
    return WatermelonDsPatchResult(success = true, message = context.getString(R.string.watermelonds_revert_success))
}

fun checkIsWatermelonDsPatched(context: Context): Boolean =
    context.getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(PrefsConstants.KEY_WATERMELONDS_PATCHED_THIS_RUN, false)

internal fun supportsWatermelonDsBroadcastOverride(context: Context, packageName: String): Boolean {
    val receiverComponent = ComponentName(packageName, WATERMELONDS_RECEIVER_CLASS)
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

private fun sendWatermelonDsBroadcast(
    context: Context,
    packageName: String,
    action: String,
    hostOverride: String? = null
) {
    val intent = Intent(action)
        .setPackage(packageName)
        .setComponent(ComponentName(packageName, WATERMELONDS_RECEIVER_CLASS))
        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)

    if (hostOverride != null) {
        intent.putExtra(WATERMELONDS_HOST_OVERRIDE_EXTRA, hostOverride)
    }

    context.sendBroadcast(intent)
}
