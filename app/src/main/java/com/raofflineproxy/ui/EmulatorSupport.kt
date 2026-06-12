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

internal const val UI_PPSSPP_PACKAGE = "org.ppsspp.ppsspp"
internal const val UI_PPSSPP_GOLD_PACKAGE = "org.ppsspp.ppssppgold"

internal val UI_PPSSPP_PACKAGE_CANDIDATES = listOf(
    UI_PPSSPP_PACKAGE,
    UI_PPSSPP_GOLD_PACKAGE
)

internal fun resolveInstalledPackage(context: Context, packageCandidates: List<String>): String? =
    packageCandidates.firstOrNull { packageName ->
        runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess
    }

internal data class EmulatorSupport(
    val retroArchInstalled: Boolean,
    val dolphinInstalled: Boolean,
    val ppssppInstalled: Boolean,
    val armsx2Installed: Boolean,
    val flycastInstalled: Boolean,
    val retroArchEnabled: Boolean,
    val dolphinEnabled: Boolean,
    val ppssppEnabled: Boolean,
    val armsx2Enabled: Boolean,
    val flycastEnabled: Boolean
) {
    val installedCount: Int = listOf(retroArchInstalled, dolphinInstalled, ppssppInstalled, armsx2Installed, flycastInstalled).count { it }
    val hasAnyEnabled: Boolean = retroArchEnabled || dolphinEnabled || ppssppEnabled || armsx2Enabled || flycastEnabled
    val hasAnyShizukuManagedEnabled: Boolean = retroArchEnabled || dolphinEnabled || ppssppEnabled
}

internal fun loadEmulatorSupport(context: Context): EmulatorSupport {
    val prefs = context.getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE)
    val retroArchPackage = resolveInstalledPackage(context, UI_RETROARCH_PACKAGE_CANDIDATES)
    val dolphinPackage = resolveInstalledPackage(context, UI_DOLPHIN_PACKAGE_CANDIDATES)
    val ppssppPackage = resolveInstalledPackage(context, UI_PPSSPP_PACKAGE_CANDIDATES)
    val armsx2Package = resolveInstalledPackage(context, UI_ARMSX2_PACKAGE_CANDIDATES)
    val flycastPackage = resolveInstalledPackage(context, UI_FLYCAST_PACKAGE_CANDIDATES)
    val retroArchInstalled = retroArchPackage != null
    val dolphinInstalled = dolphinPackage != null
    val ppssppInstalled = ppssppPackage != null
    val armsx2Installed = armsx2Package != null && supportsArmsx2BroadcastOverride(context, armsx2Package)
    val flycastInstalled = flycastPackage != null && supportsFlycastBroadcastOverride(context, flycastPackage)

    Log.i("RAProxy/Emulators", "resolved packages retroArch=$retroArchPackage dolphin=$dolphinPackage ppsspp=$ppssppPackage armsx2=$armsx2Package flycast=$flycastPackage")

    val installedCount = listOf(retroArchInstalled, dolphinInstalled, ppssppInstalled, armsx2Installed, flycastInstalled).count { it }

    val retroArchEnabled = when {
        !retroArchInstalled -> false
        installedCount == 1 -> true
        else -> prefs.getBoolean(PrefsConstants.KEY_ENABLE_RETROARCH, true)
    }

    val dolphinEnabled = when {
        !dolphinInstalled -> false
        installedCount == 1 -> true
        else -> prefs.getBoolean(PrefsConstants.KEY_ENABLE_DOLPHIN, true)
    }

    val ppssppEnabled = when {
        !ppssppInstalled -> false
        installedCount == 1 -> true
        else -> prefs.getBoolean(PrefsConstants.KEY_ENABLE_PPSSPP, true)
    }

    val armsx2Enabled = when {
        !armsx2Installed -> false
        installedCount == 1 -> true
        else -> prefs.getBoolean(PrefsConstants.KEY_ENABLE_ARMSX2, true)
    }

    val flycastEnabled = when {
        !flycastInstalled -> false
        installedCount == 1 -> true
        else -> prefs.getBoolean(PrefsConstants.KEY_ENABLE_FLYCAST, true)
    }

    return EmulatorSupport(
        retroArchInstalled = retroArchInstalled,
        dolphinInstalled = dolphinInstalled,
        ppssppInstalled = ppssppInstalled,
        armsx2Installed = armsx2Installed,
        flycastInstalled = flycastInstalled,
        retroArchEnabled = retroArchEnabled,
        dolphinEnabled = dolphinEnabled,
        ppssppEnabled = ppssppEnabled,
        armsx2Enabled = armsx2Enabled,
        flycastEnabled = flycastEnabled
    )
}
