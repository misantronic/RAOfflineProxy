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

internal const val UI_MUPEN64_PACKAGE = "org.mupen64plusae.v3.alpha"
internal const val UI_MUPEN64_DEBUG_PACKAGE = "org.mupen64plusae.v3.alpha.debug"

internal val UI_MUPEN64_PACKAGE_CANDIDATES = listOf(
    UI_MUPEN64_PACKAGE,
    UI_MUPEN64_DEBUG_PACKAGE
)

private const val MUPEN64_RECEIVER_CLASS = "paulscode.android.mupen64plusae.jni.RetroAchievementsHostOverrideReceiver"
private const val MUPEN64_SET_ACTION_SUFFIX = ".action.SET_RETROACHIEVEMENTS_HOST_OVERRIDE"
private const val MUPEN64_CLEAR_ACTION_SUFFIX = ".action.CLEAR_RETROACHIEVEMENTS_HOST_OVERRIDE"
private const val MUPEN64_HOST_OVERRIDE_EXTRA = "host"

data class Mupen64PatchResult(
    val success: Boolean,
    val message: String,
    val skippedNotInstalled: Boolean = false
)

fun patchMupen64Cfg(context: Context): Mupen64PatchResult {
    val packageName = resolveInstalledPackage(context, UI_MUPEN64_PACKAGE_CANDIDATES)
        ?: return Mupen64PatchResult(
            success = true,
            message = "Mupen64Plus not installed.",
            skippedNotInstalled = true
        )
    if (!supportsMupen64BroadcastOverride(context, packageName)) {
        return Mupen64PatchResult(success = false, message = context.getString(R.string.mupen64_patch_error_unavailable))
    }

    sendMupen64Broadcast(
        context = context,
        packageName = packageName,
        action = packageName + MUPEN64_SET_ACTION_SUFFIX,
        hostOverride = proxyBase(proxyPort(context))
    )
    return Mupen64PatchResult(success = true, message = context.getString(R.string.mupen64_patch_success))
}

fun revertMupen64Cfg(context: Context): Mupen64PatchResult {
    val packageName = resolveInstalledPackage(context, UI_MUPEN64_PACKAGE_CANDIDATES)
        ?: return Mupen64PatchResult(
            success = true,
            message = "Mupen64Plus not installed.",
            skippedNotInstalled = true
        )
    if (!supportsMupen64BroadcastOverride(context, packageName)) {
        return Mupen64PatchResult(success = false, message = context.getString(R.string.mupen64_revert_error_unavailable))
    }

    sendMupen64Broadcast(
        context = context,
        packageName = packageName,
        action = packageName + MUPEN64_CLEAR_ACTION_SUFFIX
    )
    return Mupen64PatchResult(success = true, message = context.getString(R.string.mupen64_revert_success))
}

fun checkIsMupen64Patched(context: Context): Boolean =
    context.getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(PrefsConstants.KEY_MUPEN64_PATCHED_THIS_RUN, false)

internal fun supportsMupen64BroadcastOverride(context: Context, packageName: String): Boolean {
    val receiverComponent = ComponentName(packageName, MUPEN64_RECEIVER_CLASS)
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

private fun sendMupen64Broadcast(
    context: Context,
    packageName: String,
    action: String,
    hostOverride: String? = null
) {
    val intent = Intent(action)
        .setPackage(packageName)
        .setComponent(ComponentName(packageName, MUPEN64_RECEIVER_CLASS))
        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)

    if (hostOverride != null) {
        intent.putExtra(MUPEN64_HOST_OVERRIDE_EXTRA, hostOverride)
    }

    context.sendBroadcast(intent)
}
