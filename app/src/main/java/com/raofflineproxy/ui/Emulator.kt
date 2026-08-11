package com.raofflineproxy.ui

import android.content.Context
import android.net.Uri
import com.raofflineproxy.PrefsConstants
import com.raofflineproxy.R
import com.raofflineproxy.proxy.LoginCredentials

internal const val UI_ARMSX1_PACKAGE = "com.nanodata.armsx"
internal const val UI_ARMSX2_LEGACY_PACKAGE = "come.nanodata.armsx2"
internal const val UI_ARMSX2_PACKAGE = "com.armsx2"
internal const val UI_FLYCAST_PACKAGE = "com.flycast.emulator"
internal const val UI_WATERMELONDS_PACKAGE = "me.magnum.melondualds"
internal const val UI_MUPEN64_PACKAGE = "org.mupen64plusae.v3.alpha"
internal const val UI_MUPEN64_DEBUG_PACKAGE = "org.mupen64plusae.v3.alpha.debug"
internal const val UI_EMUCOREX_PACKAGE = "com.sbro.emucorex"

internal class ConfigOverride(
    // Wire identifier for the Shizuku user service, which runs in its own process and dispatches
    // on this string. Both sides read it from here so they cannot drift apart.
    val shizukuKey: String,
    val hardcoreWasEnabledPrefsKey: String,
    val needsCredentials: Boolean = false,
    val loadSafUri: (Context) -> Uri?,
    val detectHardcoreEnabled: (String) -> Boolean,
    val patch: (Context, Uri?, LoginCredentials?) -> ConfigPatchResult,
    val revert: (Context, Uri?, Boolean) -> ConfigPatchResult
)

internal class BroadcastOverride(
    val patchSuccessRes: Int,
    val patchErrorRes: Int,
    val revertSuccessRes: Int,
    val revertErrorRes: Int,
    private val defaultReceiverClass: String,
    private val receiverClassByPackage: Map<String, String> = emptyMap()
) {
    fun receiverClassFor(packageName: String): String =
        receiverClassByPackage[packageName] ?: defaultReceiverClass
}

enum class Emulator(
    val displayName: String,
    val labelRes: Int,
    val enabledPrefsKey: String,
    val patchedThisRunPrefsKey: String,
    val packageCandidates: List<String>,
    internal val configOverride: ConfigOverride? = null,
    internal val broadcastOverride: BroadcastOverride? = null
) {
    RetroArch(
        displayName = "RetroArch",
        labelRes = R.string.emulator_retroarch,
        enabledPrefsKey = PrefsConstants.KEY_ENABLE_RETROARCH,
        patchedThisRunPrefsKey = PrefsConstants.KEY_RETROARCH_PATCHED_THIS_RUN,
        packageCandidates = RETROARCH_PACKAGE_CANDIDATES,
        configOverride = ConfigOverride(
            shizukuKey = "retroarch",
            hardcoreWasEnabledPrefsKey = PrefsConstants.KEY_RETROARCH_HARDCORE_WAS_ENABLED,
            loadSafUri = { context -> PrefsConstants.loadSafUri(context) },
            detectHardcoreEnabled = ::detectHardcoreEnabled,
            patch = { context, treeUri, _ -> patchRetroArchCfg(context, treeUri) },
            revert = { context, treeUri, restoreHardcore ->
                revertRetroArchCfg(context, treeUri, restoreHardcore)
            }
        )
    ),
    Dolphin(
        displayName = "Dolphin",
        labelRes = R.string.emulator_dolphin,
        enabledPrefsKey = PrefsConstants.KEY_ENABLE_DOLPHIN,
        patchedThisRunPrefsKey = PrefsConstants.KEY_DOLPHIN_PATCHED_THIS_RUN,
        packageCandidates = DOLPHIN_PACKAGE_CANDIDATES,
        configOverride = ConfigOverride(
            shizukuKey = "dolphin",
            hardcoreWasEnabledPrefsKey = PrefsConstants.KEY_DOLPHIN_HARDCORE_WAS_ENABLED,
            needsCredentials = true,
            loadSafUri = { context -> PrefsConstants.loadDolphinSafUri(context) },
            detectHardcoreEnabled = ::detectDolphinHardcoreEnabled,
            patch = { context, treeUri, credentials -> patchDolphinCfg(context, treeUri, credentials) },
            revert = { context, treeUri, restoreHardcore ->
                revertDolphinCfg(context, treeUri, restoreHardcore)
            }
        )
    ),
    Ppsspp(
        displayName = "PPSSPP",
        labelRes = R.string.emulator_ppsspp,
        enabledPrefsKey = PrefsConstants.KEY_ENABLE_PPSSPP,
        patchedThisRunPrefsKey = PrefsConstants.KEY_PPSSPP_PATCHED_THIS_RUN,
        packageCandidates = UI_PPSSPP_PACKAGE_CANDIDATES,
        configOverride = ConfigOverride(
            shizukuKey = "ppsspp",
            hardcoreWasEnabledPrefsKey = PrefsConstants.KEY_PPSSPP_HARDCORE_WAS_ENABLED,
            loadSafUri = { context -> PrefsConstants.loadPpssppSafUri(context) },
            detectHardcoreEnabled = ::detectPpssppHardcoreEnabled,
            patch = { context, treeUri, _ -> patchPpssppCfg(context, treeUri) },
            revert = { context, treeUri, restoreHardcore ->
                revertPpssppCfg(context, treeUri, restoreHardcore)
            }
        )
    ),
    Armsx1(
        displayName = "ARMSX1",
        labelRes = R.string.emulator_armsx1,
        enabledPrefsKey = PrefsConstants.KEY_ENABLE_ARMSX1,
        patchedThisRunPrefsKey = PrefsConstants.KEY_ARMSX1_PATCHED_THIS_RUN,
        packageCandidates = listOf(UI_ARMSX1_PACKAGE),
        broadcastOverride = BroadcastOverride(
            patchSuccessRes = R.string.armsx1_patch_success,
            patchErrorRes = R.string.armsx1_patch_error_unavailable,
            revertSuccessRes = R.string.armsx1_revert_success,
            revertErrorRes = R.string.armsx1_revert_error_unavailable,
            // The receiver ships under the com.armsx2 Java package (ARMSX1's Android app reuses
            // ARMSX2's Compose UI tree and only repointed the Gradle namespace, not every package
            // statement) even though the app's own applicationId is com.nanodata.armsx. Verified
            // against the actual 0.1 release APK via `aapt dump xmltree`, not just source.
            defaultReceiverClass = "com.armsx2.RetroAchievementsHostOverrideReceiver"
        )
    ),
    Armsx2(
        displayName = "ARMSX2",
        labelRes = R.string.emulator_armsx2,
        enabledPrefsKey = PrefsConstants.KEY_ENABLE_ARMSX2,
        patchedThisRunPrefsKey = PrefsConstants.KEY_ARMSX2_PATCHED_THIS_RUN,
        packageCandidates = listOf(UI_ARMSX2_LEGACY_PACKAGE, UI_ARMSX2_PACKAGE),
        broadcastOverride = BroadcastOverride(
            patchSuccessRes = R.string.armsx2_patch_success,
            patchErrorRes = R.string.armsx2_patch_error_unavailable,
            revertSuccessRes = R.string.armsx2_revert_success,
            revertErrorRes = R.string.armsx2_revert_error_unavailable,
            // The current line (com.armsx2) ships the receiver in its own namespace; the
            // legacy line (come.nanodata.armsx2) keeps the upstream kr.co.iefriends path.
            defaultReceiverClass = "kr.co.iefriends.pcsx2.utils.RetroAchievementsHostOverrideReceiver",
            receiverClassByPackage = mapOf(
                UI_ARMSX2_PACKAGE to "com.armsx2.RetroAchievementsHostOverrideReceiver"
            )
        )
    ),
    Flycast(
        displayName = "Flycast",
        labelRes = R.string.emulator_flycast,
        enabledPrefsKey = PrefsConstants.KEY_ENABLE_FLYCAST,
        patchedThisRunPrefsKey = PrefsConstants.KEY_FLYCAST_PATCHED_THIS_RUN,
        packageCandidates = listOf(UI_FLYCAST_PACKAGE),
        broadcastOverride = BroadcastOverride(
            patchSuccessRes = R.string.flycast_patch_success,
            patchErrorRes = R.string.flycast_patch_error_unavailable,
            revertSuccessRes = R.string.flycast_revert_success,
            revertErrorRes = R.string.flycast_revert_error_unavailable,
            defaultReceiverClass = "com.flycast.emulator.RetroAchievementsHostOverrideReceiver"
        )
    ),
    WatermelonDs(
        displayName = "WatermelonDS",
        labelRes = R.string.emulator_watermelonds,
        enabledPrefsKey = PrefsConstants.KEY_ENABLE_WATERMELONDS,
        patchedThisRunPrefsKey = PrefsConstants.KEY_WATERMELONDS_PATCHED_THIS_RUN,
        packageCandidates = listOf(UI_WATERMELONDS_PACKAGE),
        broadcastOverride = BroadcastOverride(
            patchSuccessRes = R.string.watermelonds_patch_success,
            patchErrorRes = R.string.watermelonds_patch_error_unavailable,
            revertSuccessRes = R.string.watermelonds_revert_success,
            revertErrorRes = R.string.watermelonds_revert_error_unavailable,
            defaultReceiverClass = "me.magnum.melondualds.RetroAchievementsHostOverrideReceiver"
        )
    ),
    Mupen64(
        displayName = "Mupen64Plus",
        labelRes = R.string.emulator_mupen64,
        enabledPrefsKey = PrefsConstants.KEY_ENABLE_MUPEN64,
        patchedThisRunPrefsKey = PrefsConstants.KEY_MUPEN64_PATCHED_THIS_RUN,
        packageCandidates = listOf(UI_MUPEN64_PACKAGE, UI_MUPEN64_DEBUG_PACKAGE),
        broadcastOverride = BroadcastOverride(
            patchSuccessRes = R.string.mupen64_patch_success,
            patchErrorRes = R.string.mupen64_patch_error_unavailable,
            revertSuccessRes = R.string.mupen64_revert_success,
            revertErrorRes = R.string.mupen64_revert_error_unavailable,
            defaultReceiverClass = "paulscode.android.mupen64plusae.jni.RetroAchievementsHostOverrideReceiver"
        )
    ),
    EmuCoreX(
        displayName = "EmuCoreX",
        labelRes = R.string.emulator_emucorex,
        enabledPrefsKey = PrefsConstants.KEY_ENABLE_EMUCOREX,
        patchedThisRunPrefsKey = PrefsConstants.KEY_EMUCOREX_PATCHED_THIS_RUN,
        packageCandidates = listOf(UI_EMUCOREX_PACKAGE),
        broadcastOverride = BroadcastOverride(
            patchSuccessRes = R.string.emucorex_patch_success,
            patchErrorRes = R.string.emucorex_patch_error_unavailable,
            revertSuccessRes = R.string.emucorex_revert_success,
            revertErrorRes = R.string.emucorex_revert_error_unavailable,
            defaultReceiverClass = "com.sbro.emucorex.core.utils.RetroAchievementsHostOverrideReceiver"
        )
    );

    companion object {
        // The config-file emulators are exactly the ones the Shizuku user service knows how to
        // rewrite; everything else is repointed at runtime with a host-override broadcast.
        val SHIZUKU_MANAGED: List<Emulator> by lazy { entries.filter { it.configOverride != null } }
        val BROADCAST_MANAGED: List<Emulator> by lazy { entries.filter { it.broadcastOverride != null } }
    }
}
