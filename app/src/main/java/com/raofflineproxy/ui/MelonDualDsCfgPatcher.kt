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

internal const val UI_MELONDUALDS_PACKAGE = "me.magnum.melondualds"

internal val UI_MELONDUALDS_PACKAGE_CANDIDATES = listOf(
    UI_MELONDUALDS_PACKAGE
)

private const val MELONDUALDS_RECEIVER_CLASS = "me.magnum.melondualds.RetroAchievementsHostOverrideReceiver"
private const val MELONDUALDS_SET_ACTION_SUFFIX = ".action.SET_RETROACHIEVEMENTS_HOST_OVERRIDE"
private const val MELONDUALDS_CLEAR_ACTION_SUFFIX = ".action.CLEAR_RETROACHIEVEMENTS_HOST_OVERRIDE"
private const val MELONDUALDS_HOST_OVERRIDE_EXTRA = "host"

data class MelonDualDsPatchResult(
    val success: Boolean,
    val message: String,
    val skippedNotInstalled: Boolean = false
)

fun patchMelonDualDsCfg(context: Context): MelonDualDsPatchResult {
    val packageName = resolveInstalledPackage(context, UI_MELONDUALDS_PACKAGE_CANDIDATES)
        ?: return MelonDualDsPatchResult(
            success = true,
            message = "melonDualDS not installed.",
            skippedNotInstalled = true
        )
    if (!supportsMelonDualDsBroadcastOverride(context, packageName)) {
        return MelonDualDsPatchResult(success = false, message = context.getString(R.string.melondualds_patch_error_unavailable))
    }

    sendMelonDualDsBroadcast(
        context = context,
        packageName = packageName,
        action = packageName + MELONDUALDS_SET_ACTION_SUFFIX,
        hostOverride = proxyBase(proxyPort(context))
    )
    return MelonDualDsPatchResult(success = true, message = context.getString(R.string.melondualds_patch_success))
}

fun revertMelonDualDsCfg(context: Context): MelonDualDsPatchResult {
    val packageName = resolveInstalledPackage(context, UI_MELONDUALDS_PACKAGE_CANDIDATES)
        ?: return MelonDualDsPatchResult(
            success = true,
            message = "melonDualDS not installed.",
            skippedNotInstalled = true
        )
    if (!supportsMelonDualDsBroadcastOverride(context, packageName)) {
        return MelonDualDsPatchResult(success = false, message = context.getString(R.string.melondualds_revert_error_unavailable))
    }

    sendMelonDualDsBroadcast(
        context = context,
        packageName = packageName,
        action = packageName + MELONDUALDS_CLEAR_ACTION_SUFFIX
    )
    return MelonDualDsPatchResult(success = true, message = context.getString(R.string.melondualds_revert_success))
}

fun checkIsMelonDualDsPatched(context: Context): Boolean =
    context.getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(PrefsConstants.KEY_MELONDUALDS_PATCHED_THIS_RUN, false)

internal fun supportsMelonDualDsBroadcastOverride(context: Context, packageName: String): Boolean {
    val receiverComponent = ComponentName(packageName, MELONDUALDS_RECEIVER_CLASS)
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

private fun sendMelonDualDsBroadcast(
    context: Context,
    packageName: String,
    action: String,
    hostOverride: String? = null
) {
    val intent = Intent(action)
        .setPackage(packageName)
        .setComponent(ComponentName(packageName, MELONDUALDS_RECEIVER_CLASS))
        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)

    if (hostOverride != null) {
        intent.putExtra(MELONDUALDS_HOST_OVERRIDE_EXTRA, hostOverride)
    }

    context.sendBroadcast(intent)
}
