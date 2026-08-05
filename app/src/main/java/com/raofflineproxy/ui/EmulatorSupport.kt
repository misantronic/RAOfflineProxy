package com.raofflineproxy.ui

import android.content.Context
import android.util.Log
import com.raofflineproxy.PrefsConstants

private val UI_RETROARCH_PACKAGE_CANDIDATES = listOf(
    "com.retroarch.aarch64",
    "com.retroarch"
)

private val UI_DOLPHIN_PACKAGE_CANDIDATES = DOLPHIN_PACKAGE_CANDIDATES

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
    val melonDualDsInstalled: Boolean,
    val mupen64Installed: Boolean,
    val emuCoreXInstalled: Boolean,
    val armsx1Installed: Boolean,
    val retroArchEnabled: Boolean,
    val dolphinEnabled: Boolean,
    val ppssppEnabled: Boolean,
    val armsx2Enabled: Boolean,
    val flycastEnabled: Boolean,
    val melonDualDsEnabled: Boolean,
    val mupen64Enabled: Boolean,
    val emuCoreXEnabled: Boolean,
    val armsx1Enabled: Boolean
) {
    val installedCount: Int = listOf(retroArchInstalled, dolphinInstalled, ppssppInstalled, armsx2Installed, flycastInstalled, melonDualDsInstalled, mupen64Installed, emuCoreXInstalled, armsx1Installed).count { it }
    val hasAnyEnabled: Boolean = retroArchEnabled || dolphinEnabled || ppssppEnabled || armsx2Enabled || flycastEnabled || melonDualDsEnabled || mupen64Enabled || emuCoreXEnabled || armsx1Enabled
    val hasAnyShizukuManagedEnabled: Boolean = retroArchEnabled || dolphinEnabled || ppssppEnabled
}

internal fun loadEmulatorSupport(context: Context): EmulatorSupport {
    val prefs = context.getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE)
    val retroArchPackage = resolveInstalledPackage(context, UI_RETROARCH_PACKAGE_CANDIDATES)
    val dolphinPackage = resolveInstalledPackage(context, UI_DOLPHIN_PACKAGE_CANDIDATES)
    val ppssppPackage = resolveInstalledPackage(context, UI_PPSSPP_PACKAGE_CANDIDATES)
    val armsx2Package = resolveInstalledPackage(context, UI_ARMSX2_PACKAGE_CANDIDATES)
    val flycastPackage = resolveInstalledPackage(context, UI_FLYCAST_PACKAGE_CANDIDATES)
    val melonDualDsPackage = resolveInstalledPackage(context, UI_MELONDUALDS_PACKAGE_CANDIDATES)
    val mupen64Package = resolveInstalledPackage(context, UI_MUPEN64_PACKAGE_CANDIDATES)
    val emuCoreXPackage = resolveInstalledPackage(context, UI_EMUCOREX_PACKAGE_CANDIDATES)
    val armsx1Package = resolveInstalledPackage(context, UI_ARMSX1_PACKAGE_CANDIDATES)
    val retroArchInstalled = retroArchPackage != null
    val dolphinInstalled = dolphinPackage != null
    val ppssppInstalled = ppssppPackage != null
    val armsx2Installed = armsx2Package != null && supportsArmsx2BroadcastOverride(context, armsx2Package)
    val flycastInstalled = flycastPackage != null && supportsFlycastBroadcastOverride(context, flycastPackage)
    val melonDualDsInstalled = melonDualDsPackage != null && supportsMelonDualDsBroadcastOverride(context, melonDualDsPackage)
    val mupen64Installed = mupen64Package != null && supportsMupen64BroadcastOverride(context, mupen64Package)
    val emuCoreXInstalled = emuCoreXPackage != null && supportsEmuCoreXBroadcastOverride(context, emuCoreXPackage)
    val armsx1Installed = armsx1Package != null && supportsArmsx1BroadcastOverride(context, armsx1Package)

    Log.i("RAProxy/Emulators", "resolved packages retroArch=$retroArchPackage dolphin=$dolphinPackage ppsspp=$ppssppPackage armsx2=$armsx2Package flycast=$flycastPackage melonDualDs=$melonDualDsPackage mupen64=$mupen64Package emuCoreX=$emuCoreXPackage armsx1=$armsx1Package")

    val installedCount = listOf(retroArchInstalled, dolphinInstalled, ppssppInstalled, armsx2Installed, flycastInstalled, melonDualDsInstalled, mupen64Installed, emuCoreXInstalled, armsx1Installed).count { it }

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

    val melonDualDsEnabled = when {
        !melonDualDsInstalled -> false
        installedCount == 1 -> true
        else -> prefs.getBoolean(PrefsConstants.KEY_ENABLE_MELONDUALDS, true)
    }

    val mupen64Enabled = when {
        !mupen64Installed -> false
        installedCount == 1 -> true
        else -> prefs.getBoolean(PrefsConstants.KEY_ENABLE_MUPEN64, true)
    }

    val emuCoreXEnabled = when {
        !emuCoreXInstalled -> false
        installedCount == 1 -> true
        else -> prefs.getBoolean(PrefsConstants.KEY_ENABLE_EMUCOREX, true)
    }

    val armsx1Enabled = when {
        !armsx1Installed -> false
        installedCount == 1 -> true
        else -> prefs.getBoolean(PrefsConstants.KEY_ENABLE_ARMSX1, true)
    }

    return EmulatorSupport(
        retroArchInstalled = retroArchInstalled,
        dolphinInstalled = dolphinInstalled,
        ppssppInstalled = ppssppInstalled,
        armsx2Installed = armsx2Installed,
        flycastInstalled = flycastInstalled,
        melonDualDsInstalled = melonDualDsInstalled,
        mupen64Installed = mupen64Installed,
        emuCoreXInstalled = emuCoreXInstalled,
        armsx1Installed = armsx1Installed,
        retroArchEnabled = retroArchEnabled,
        dolphinEnabled = dolphinEnabled,
        ppssppEnabled = ppssppEnabled,
        armsx2Enabled = armsx2Enabled,
        flycastEnabled = flycastEnabled,
        melonDualDsEnabled = melonDualDsEnabled,
        mupen64Enabled = mupen64Enabled,
        emuCoreXEnabled = emuCoreXEnabled,
        armsx1Enabled = armsx1Enabled
    )
}
