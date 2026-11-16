package com.raofflineproxy.ui

import android.content.Context
import android.net.Uri
import com.raofflineproxy.proxy.LoginCredentials

data class ConfigPatchResult(
    val success: Boolean,
    val message: String,
    val needsSafGrant: Boolean = false,
    val invalidSafGrant: Boolean = false,
    val copyBackPath: String? = null,
    val hardcoreWasEnabled: Boolean = false,
    val credentials: ImportedCredentials? = null,
    val skippedNotInstalled: Boolean = false
)

internal fun requireConfigOverride(emulator: Emulator): ConfigOverride =
    requireNotNull(emulator.configOverride) {
        "${emulator.displayName} is not patched via config file"
    }

fun loadConfigSafUri(context: Context, emulator: Emulator): Uri? =
    requireConfigOverride(emulator).loadSafUri(context)

fun patchConfigCfg(
    context: Context,
    emulator: Emulator,
    treeUri: Uri?,
    credentials: LoginCredentials? = null
): ConfigPatchResult = requireConfigOverride(emulator).patch(context, treeUri, credentials)

fun revertConfigCfg(
    context: Context,
    emulator: Emulator,
    treeUri: Uri?,
    restoreHardcore: Boolean = false
): ConfigPatchResult = requireConfigOverride(emulator).revert(context, treeUri, restoreHardcore)

fun configDisabledResult(emulator: Emulator): ConfigPatchResult = ConfigPatchResult(
    success = true,
    message = "${emulator.displayName} disabled.",
    skippedNotInstalled = true
)

fun configNotPatchedResult(emulator: Emulator): ConfigPatchResult = ConfigPatchResult(
    success = true,
    message = "${emulator.displayName} not patched this run.",
    skippedNotInstalled = true
)
