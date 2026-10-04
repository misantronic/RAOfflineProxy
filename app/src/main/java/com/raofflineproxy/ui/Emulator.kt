package com.raofflineproxy.ui

import android.content.Context
import android.net.Uri
import com.raofflineproxy.PrefsConstants
import com.raofflineproxy.R
import com.raofflineproxy.proxy.LoginCredentials
import com.raofflineproxy.proxyBase
import com.raofflineproxy.proxyPort
import com.raofflineproxy.proxyValue


internal class ConfigOverride(
    // Wire identifier for the Shizuku user service, which runs in its own process and dispatches
    // on this string. Both sides read it from here so they cannot drift apart.
    val shizukuKey: String,
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
    private val receiverClassByPackage: Map<String, String> = emptyMap(),
    val hostValue: (Context) -> String = { context -> proxyBase(proxyPort(context)) }
) {
    fun receiverClassFor(packageName: String): String =
        receiverClassByPackage[packageName] ?: defaultReceiverClass
}

enum class Emulator(
    val displayName: String,
    val labelRes: Int,
    val prefsId: String,
    val packageCandidates: List<String>,
    internal val configOverride: ConfigOverride? = null,
    internal val broadcastOverride: BroadcastOverride? = null
) {
    RetroArch(
        displayName = "RetroArch",
        labelRes = R.string.emulator_retroarch,
        prefsId = "retroarch",
        packageCandidates = listOf("com.retroarch.aarch64", "com.retroarch"),
        configOverride = ConfigOverride(
            shizukuKey = "retroarch",
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
        prefsId = "dolphin",
        packageCandidates = listOf(
            "org.dolphinemu.dolphinemu",
            "org.dolphinemu.dolphinemu.beta",
            "org.dolphinemu.dolphinemu.debug",
            "com.joeyos.dolphinemu"
        ),
        configOverride = ConfigOverride(
            shizukuKey = "dolphin",
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
        prefsId = "ppsspp",
        packageCandidates = listOf("org.ppsspp.ppsspp", "org.ppsspp.ppssppgold"),
        configOverride = ConfigOverride(
            shizukuKey = "ppsspp",
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
        prefsId = "armsx1",
        packageCandidates = listOf("com.nanodata.armsx"),
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
        prefsId = "armsx2",
        packageCandidates = listOf("come.nanodata.armsx2", "com.armsx2"),
        broadcastOverride = BroadcastOverride(
            patchSuccessRes = R.string.armsx2_patch_success,
            patchErrorRes = R.string.armsx2_patch_error_unavailable,
            revertSuccessRes = R.string.armsx2_revert_success,
            revertErrorRes = R.string.armsx2_revert_error_unavailable,
            // The current line (com.armsx2) ships the receiver in its own namespace; the
            // legacy line (come.nanodata.armsx2) keeps the upstream kr.co.iefriends path.
            defaultReceiverClass = "kr.co.iefriends.pcsx2.utils.RetroAchievementsHostOverrideReceiver",
            receiverClassByPackage = mapOf(
                "com.armsx2" to "com.armsx2.RetroAchievementsHostOverrideReceiver"
            )
        )
    ),
    Flycast(
        displayName = "Flycast",
        labelRes = R.string.emulator_flycast,
        prefsId = "flycast",
        packageCandidates = listOf("com.flycast.emulator"),
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
        prefsId = "melondualds",
        packageCandidates = listOf("me.magnum.melondualds"),
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
        prefsId = "mupen64",
        packageCandidates = listOf("org.mupen64plusae.v3.alpha", "org.mupen64plusae.v3.alpha.debug"),
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
        prefsId = "emucorex",
        packageCandidates = listOf("com.sbro.emucorex"),
        broadcastOverride = BroadcastOverride(
            patchSuccessRes = R.string.emucorex_patch_success,
            patchErrorRes = R.string.emucorex_patch_error_unavailable,
            revertSuccessRes = R.string.emucorex_revert_success,
            revertErrorRes = R.string.emucorex_revert_error_unavailable,
            defaultReceiverClass = "com.sbro.emucorex.core.utils.RetroAchievementsHostOverrideReceiver"
        )
    ),
    NetherSx2(
        displayName = "NetherSX2",
        labelRes = R.string.emulator_nethersx2,
        prefsId = "nethersx2",
        packageCandidates = listOf("xyz.aethersx2.android"),
        broadcastOverride = BroadcastOverride(
            patchSuccessRes = R.string.nethersx2_patch_success,
            patchErrorRes = R.string.nethersx2_patch_error_unavailable,
            revertSuccessRes = R.string.nethersx2_revert_success,
            revertErrorRes = R.string.nethersx2_revert_error_unavailable,
            defaultReceiverClass = "xyz.aethersx2.android.RetroAchievementsHostOverrideReceiver",
            // NetherSX2 binary-patches the host into libemucore.so and expects it without a scheme.
            hostValue = ::proxyValue
        )
    ),
    SeedlessDs(
        displayName = "SeedlessDS",
        labelRes = R.string.emulator_seedlessds,
        prefsId = "seedlessds",
        packageCandidates = listOf("com.seedlessds.app", "com.seedlessds.app.debug"),
        broadcastOverride = BroadcastOverride(
            patchSuccessRes = R.string.seedlessds_patch_success,
            patchErrorRes = R.string.seedlessds_patch_error_unavailable,
            revertSuccessRes = R.string.seedlessds_revert_success,
            revertErrorRes = R.string.seedlessds_revert_error_unavailable,
            defaultReceiverClass = "com.seedlessds.app.ra.RaHostOverrideReceiver"
        )
    );

    val enabledPrefsKey: String get() = "enable_$prefsId"
    val patchedThisRunPrefsKey: String get() = "${prefsId}_patched_this_run"
    val hardcoreWasEnabledPrefsKey: String get() = "${prefsId}_hardcore_was_enabled"

    companion object {
        // The config-file emulators are exactly the ones the Shizuku user service knows how to
        // rewrite; everything else is repointed at runtime with a host-override broadcast.
        val SHIZUKU_MANAGED: List<Emulator> by lazy { entries.filter { it.configOverride != null } }
        val BROADCAST_MANAGED: List<Emulator> by lazy { entries.filter { it.broadcastOverride != null } }
    }
}
