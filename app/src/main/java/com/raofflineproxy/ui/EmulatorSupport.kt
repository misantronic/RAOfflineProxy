package com.raofflineproxy.ui

import android.content.Context
import android.util.Log
import com.raofflineproxy.PrefsConstants

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

data class EmulatorState(
    val emulator: Emulator,
    val installed: Boolean,
    val enabled: Boolean
)

data class EmulatorSupport(val states: List<EmulatorState>) {
    val installed: List<Emulator> = states.filter { it.installed }.map { it.emulator }
    val enabled: List<Emulator> = states.filter { it.enabled }.map { it.emulator }
    val installedCount: Int = installed.size
    val hasAnyEnabled: Boolean = enabled.isNotEmpty()
    val hasAnyShizukuManagedEnabled: Boolean = enabled.any { it in Emulator.SHIZUKU_MANAGED }

    fun isInstalled(emulator: Emulator): Boolean = emulator in installed

    fun isEnabled(emulator: Emulator): Boolean = emulator in enabled

    companion object {
        val NONE = EmulatorSupport(
            Emulator.entries.map { EmulatorState(it, installed = false, enabled = false) }
        )
    }
}

internal fun loadEmulatorSupport(context: Context): EmulatorSupport {
    val prefs = context.getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE)
    val resolvedPackages = Emulator.entries.associateWith { emulator ->
        resolveInstalledPackage(context, emulator.packageCandidates)
    }

    Log.i(
        "RAProxy/Emulators",
        "resolved packages " + resolvedPackages.entries.joinToString(" ") { (emulator, packageName) ->
            "${emulator.name}=$packageName"
        }
    )

    val installedPackages = resolvedPackages.filterValues { packageName -> packageName != null }
        .filterKeys { emulator ->
            val packageName = resolvedPackages.getValue(emulator) ?: return@filterKeys false
            emulator.broadcastOverride == null || supportsBroadcastOverride(context, emulator, packageName)
        }
    val installedCount = installedPackages.size

    return EmulatorSupport(
        Emulator.entries.map { emulator ->
            val installed = installedPackages.containsKey(emulator)
            EmulatorState(
                emulator = emulator,
                installed = installed,
                enabled = when {
                    !installed -> false
                    installedCount == 1 -> true
                    else -> prefs.getBoolean(emulator.enabledPrefsKey, true)
                }
            )
        }
    )
}
