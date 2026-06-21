package com.raofflineproxy.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.raofflineproxy.R
import rikka.shizuku.Shizuku

private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
internal const val SHIZUKU_PERMISSION_REQUEST_CODE = 4014

enum class ShizukuStatus {
    Unsupported,
    NotInstalled,
    NotRunning,
    PermissionDenied,
    Ready
}

internal fun resolveShizukuStatus(context: Context): ShizukuStatus {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        return ShizukuStatus.Unsupported
    }

    if (Shizuku.pingBinder() && !Shizuku.isPreV11()) {
        return if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            ShizukuStatus.Ready
        } else {
            ShizukuStatus.PermissionDenied
        }
    }

    val installed = runCatching { context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) }.isSuccess
    return if (installed) ShizukuStatus.NotRunning else ShizukuStatus.NotInstalled
}

internal fun shizukuStatusLabel(context: Context, status: ShizukuStatus): String = when (status) {
    ShizukuStatus.Unsupported -> context.getString(R.string.manual_patching_shizuku_status_unsupported)
    ShizukuStatus.NotInstalled -> context.getString(R.string.manual_patching_shizuku_status_not_installed)
    ShizukuStatus.NotRunning -> context.getString(R.string.manual_patching_shizuku_status_not_running)
    ShizukuStatus.PermissionDenied -> context.getString(R.string.manual_patching_shizuku_status_permission_denied)
    ShizukuStatus.Ready -> context.getString(R.string.manual_patching_shizuku_status_ready)
}

internal fun canUseShizuku(context: Context): Boolean = resolveShizukuStatus(context) == ShizukuStatus.Ready
