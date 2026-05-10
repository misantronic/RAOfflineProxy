package com.raofflineproxy.ui

import android.content.Context
import android.util.Log
import com.raofflineproxy.PrefsConstants

private val UI_RETROARCH_PACKAGE_CANDIDATES = listOf(
    "com.retroarch.aarch64",
    "com.retroarch"
)

private val UI_DOLPHIN_PACKAGE_CANDIDATES = listOf(
    "org.dolphinemu.dolphinemu",
    "org.dolphinemu.dolphinemu.beta",
    "org.dolphinemu.dolphinemu.debug"
)

internal fun resolveInstalledPackage(context: Context, packageCandidates: List<String>): String? =
    packageCandidates.firstOrNull { packageName ->
        runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess
    }

internal data class EmulatorSupport(
    val retroArchInstalled: Boolean,
    val dolphinInstalled: Boolean,
    val retroArchEnabled: Boolean,
    val dolphinEnabled: Boolean
) {
    val installedCount: Int = listOf(retroArchInstalled, dolphinInstalled).count { it }
    val hasAnyEnabled: Boolean = retroArchEnabled || dolphinEnabled
}

internal fun loadEmulatorSupport(context: Context): EmulatorSupport {
    val prefs = context.getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE)
    val retroArchPackage = resolveInstalledPackage(context, UI_RETROARCH_PACKAGE_CANDIDATES)
    val dolphinPackage = resolveInstalledPackage(context, UI_DOLPHIN_PACKAGE_CANDIDATES)
    val retroArchInstalled = retroArchPackage != null
    val dolphinInstalled = dolphinPackage != null

    Log.i("RAProxy/Emulators", "resolved packages retroArch=$retroArchPackage dolphin=$dolphinPackage")

    val retroArchEnabled = when {
        !retroArchInstalled -> false
        retroArchInstalled && !dolphinInstalled -> true
        else -> prefs.getBoolean(PrefsConstants.KEY_ENABLE_RETROARCH, true)
    }

    val dolphinEnabled = when {
        !dolphinInstalled -> false
        dolphinInstalled && !retroArchInstalled -> true
        else -> prefs.getBoolean(PrefsConstants.KEY_ENABLE_DOLPHIN, false)
    }

    return EmulatorSupport(
        retroArchInstalled = retroArchInstalled,
        dolphinInstalled = dolphinInstalled,
        retroArchEnabled = retroArchEnabled,
        dolphinEnabled = dolphinEnabled
    )
}
