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

internal const val UI_ARMSX1_PACKAGE = "com.nanodata.armsx"

internal val UI_ARMSX1_PACKAGE_CANDIDATES = listOf(
    UI_ARMSX1_PACKAGE
)

// The receiver ships under the com.armsx2 Java package (ARMSX1's Android app reuses
// ARMSX2's Compose UI tree and only repointed the Gradle namespace, not every package
// statement) even though the app's own applicationId is com.nanodata.armsx. Verified
// against the actual 0.1 release APK via `aapt dump xmltree`, not just source.
private const val ARMSX1_RECEIVER_CLASS = "com.armsx2.RetroAchievementsHostOverrideReceiver"
private const val ARMSX1_SET_ACTION_SUFFIX = ".action.SET_RETROACHIEVEMENTS_HOST_OVERRIDE"
private const val ARMSX1_CLEAR_ACTION_SUFFIX = ".action.CLEAR_RETROACHIEVEMENTS_HOST_OVERRIDE"
private const val ARMSX1_HOST_OVERRIDE_EXTRA = "host"

data class Armsx1PatchResult(
    val success: Boolean,
    val message: String,
    val skippedNotInstalled: Boolean = false
)

fun patchArmsx1Cfg(context: Context): Armsx1PatchResult {
    val packageName = resolveInstalledPackage(context, UI_ARMSX1_PACKAGE_CANDIDATES)
        ?: return Armsx1PatchResult(
            success = true,
            message = "ARMSX1 not installed.",
            skippedNotInstalled = true
        )
    if (!supportsArmsx1BroadcastOverride(context, packageName)) {
        return Armsx1PatchResult(success = false, message = context.getString(R.string.armsx1_patch_error_unavailable))
    }

    sendArmsx1Broadcast(
        context = context,
        packageName = packageName,
        action = packageName + ARMSX1_SET_ACTION_SUFFIX,
        hostOverride = proxyBase(proxyPort(context))
    )
    return Armsx1PatchResult(success = true, message = context.getString(R.string.armsx1_patch_success))
}

fun revertArmsx1Cfg(context: Context): Armsx1PatchResult {
    val packageName = resolveInstalledPackage(context, UI_ARMSX1_PACKAGE_CANDIDATES)
        ?: return Armsx1PatchResult(
            success = true,
            message = "ARMSX1 not installed.",
            skippedNotInstalled = true
        )
    if (!supportsArmsx1BroadcastOverride(context, packageName)) {
        return Armsx1PatchResult(success = false, message = context.getString(R.string.armsx1_revert_error_unavailable))
    }

    sendArmsx1Broadcast(
        context = context,
        packageName = packageName,
        action = packageName + ARMSX1_CLEAR_ACTION_SUFFIX
    )
    return Armsx1PatchResult(success = true, message = context.getString(R.string.armsx1_revert_success))
}

fun checkIsArmsx1Patched(context: Context): Boolean =
    context.getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(PrefsConstants.KEY_ARMSX1_PATCHED_THIS_RUN, false)

internal fun supportsArmsx1BroadcastOverride(context: Context, packageName: String): Boolean {
    val receiverComponent = ComponentName(packageName, ARMSX1_RECEIVER_CLASS)
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

private fun sendArmsx1Broadcast(
    context: Context,
    packageName: String,
    action: String,
    hostOverride: String? = null
) {
    val intent = Intent(action)
        .setPackage(packageName)
        .setComponent(ComponentName(packageName, ARMSX1_RECEIVER_CLASS))
        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)

    if (hostOverride != null) {
        intent.putExtra(ARMSX1_HOST_OVERRIDE_EXTRA, hostOverride)
    }

    context.sendBroadcast(intent)
}
