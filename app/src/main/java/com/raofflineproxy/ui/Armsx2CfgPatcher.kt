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

internal const val UI_ARMSX2_LEGACY_PACKAGE = "come.nanodata.armsx2"
internal const val UI_ARMSX2_PACKAGE = "com.armsx2"

internal val UI_ARMSX2_PACKAGE_CANDIDATES = listOf(
    UI_ARMSX2_LEGACY_PACKAGE,
    UI_ARMSX2_PACKAGE
)

// The current line (com.armsx2) ships the receiver in its own namespace; the
// legacy line (come.nanodata.armsx2) keeps the upstream kr.co.iefriends path.
private const val ARMSX2_LEGACY_RECEIVER_CLASS = "kr.co.iefriends.pcsx2.utils.RetroAchievementsHostOverrideReceiver"
private const val ARMSX2_RECEIVER_CLASS = "com.armsx2.RetroAchievementsHostOverrideReceiver"
private const val ARMSX2_SET_ACTION_SUFFIX = ".action.SET_RETROACHIEVEMENTS_HOST_OVERRIDE"
private const val ARMSX2_CLEAR_ACTION_SUFFIX = ".action.CLEAR_RETROACHIEVEMENTS_HOST_OVERRIDE"
private const val ARMSX2_HOST_OVERRIDE_EXTRA = "host"

private fun armsx2ReceiverClass(packageName: String): String =
    if (packageName == UI_ARMSX2_PACKAGE) ARMSX2_RECEIVER_CLASS
    else ARMSX2_LEGACY_RECEIVER_CLASS

data class Armsx2PatchResult(
    val success: Boolean,
    val message: String,
    val skippedNotInstalled: Boolean = false
)

fun patchArmsx2Cfg(context: Context): Armsx2PatchResult {
    val packageName = resolveInstalledPackage(context, UI_ARMSX2_PACKAGE_CANDIDATES)
        ?: return Armsx2PatchResult(
            success = true,
            message = "ARMSX2 not installed.",
            skippedNotInstalled = true
        )
    if (!supportsArmsx2BroadcastOverride(context, packageName)) {
        return Armsx2PatchResult(success = false, message = context.getString(R.string.armsx2_patch_error_unavailable))
    }

    sendArmsx2Broadcast(
        context = context,
        packageName = packageName,
        action = packageName + ARMSX2_SET_ACTION_SUFFIX,
        hostOverride = proxyBase(proxyPort(context))
    )
    return Armsx2PatchResult(success = true, message = context.getString(R.string.armsx2_patch_success))
}

fun revertArmsx2Cfg(context: Context): Armsx2PatchResult {
    val packageName = resolveInstalledPackage(context, UI_ARMSX2_PACKAGE_CANDIDATES)
        ?: return Armsx2PatchResult(
            success = true,
            message = "ARMSX2 not installed.",
            skippedNotInstalled = true
        )
    if (!supportsArmsx2BroadcastOverride(context, packageName)) {
        return Armsx2PatchResult(success = false, message = context.getString(R.string.armsx2_revert_error_unavailable))
    }

    sendArmsx2Broadcast(
        context = context,
        packageName = packageName,
        action = packageName + ARMSX2_CLEAR_ACTION_SUFFIX
    )
    return Armsx2PatchResult(success = true, message = context.getString(R.string.armsx2_revert_success))
}

fun checkIsArmsx2Patched(context: Context): Boolean =
    context.getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(PrefsConstants.KEY_ARMSX2_PATCHED_THIS_RUN, false)

internal fun supportsArmsx2BroadcastOverride(context: Context, packageName: String): Boolean {
    val receiverComponent = ComponentName(packageName, armsx2ReceiverClass(packageName))
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

private fun sendArmsx2Broadcast(
    context: Context,
    packageName: String,
    action: String,
    hostOverride: String? = null
) {
    val intent = Intent(action)
        .setPackage(packageName)
        .setComponent(ComponentName(packageName, armsx2ReceiverClass(packageName)))
        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)

    if (hostOverride != null) {
        intent.putExtra(ARMSX2_HOST_OVERRIDE_EXTRA, hostOverride)
    }

    context.sendBroadcast(intent)
}
