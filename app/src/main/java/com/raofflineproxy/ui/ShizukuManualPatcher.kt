package com.raofflineproxy.ui

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.provider.DocumentsContract
import android.util.Log
import androidx.annotation.Keep
import androidx.core.content.edit
import com.raofflineproxy.PrefsConstants
import com.raofflineproxy.R
import com.raofflineproxy.proxyValue
import java.io.File
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

private const val SHIZUKU_ACTION_PATCH = "patch"
private const val SHIZUKU_SERVICE_TAG = "manual_emulator_patcher"
private const val TAG = "RAProxy/ShizukuManual"

internal suspend fun executeShizukuManualPatch(
    context: Context,
    support: EmulatorSupport,
    action: String,
    restoreHardcore: Map<Emulator, Boolean> = emptyMap()
): ManualPatchExecutionResult {
    if (!canUseShizuku(context)) {
        return ManualPatchExecutionResult(success = false, message = shizukuStatusLabel(context, resolveShizukuStatus(context)))
    }

    if (!support.hasAnyEnabled) {
        return ManualPatchExecutionResult(success = false, message = context.getString(R.string.proxy_start_requires_emulator))
    }

    val ppssppRootMode = PrefsConstants.loadPpssppRootMode(context)
    val ppssppIniPath = if (support.isEnabled(Emulator.Ppsspp)) {
        resolvePpssppIniPathForShizuku(context)
    } else {
        null
    }

    val requestJson = JSONObject()
        .put("action", action)
        .put("proxyAddress", proxyValue(context))
        .put("ppssppRootMode", ppssppRootMode.name)
        .put("ppssppIniPath", ppssppIniPath)
        .put("enabledEmulators", JSONArray().apply {
            Emulator.SHIZUKU_MANAGED
                .filter { support.isEnabled(it) }
                .forEach { put(requireConfigOverride(it).shizukuKey) }
        })
        .put("hardcoreWasEnabled", JSONObject().apply {
            restoreHardcore.forEach { (emulator, wasEnabled) ->
                put(requireConfigOverride(emulator).shizukuKey, wasEnabled)
            }
        })
        .toString()
    val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ShizukuManualPatcherService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("manual_patcher")
        .tag(SHIZUKU_SERVICE_TAG)
        .version(1)

    return suspendCancellableCoroutine { continuation ->
        var completed = false

        fun finish(result: ManualPatchExecutionResult, connection: ServiceConnection? = null) {
            if (completed) return
            completed = true
            if (connection != null) {
                runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
            }
            continuation.resume(result)
        }

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val patcher = IShizukuManualPatcher.Stub.asInterface(service)
                Thread {
                    val result = runCatching {
                        val rawResponse = patcher.execute(requestJson)
                        if (rawResponse.isNullOrBlank()) {
                            Log.e(TAG, "Shizuku service returned an empty response for action=$action")
                            return@runCatching ManualPatchExecutionResult(
                                success = false,
                                message = context.getString(R.string.manual_patching_shizuku_service_no_response)
                            )
                        }

                        val response = JSONObject(rawResponse)
                        val detectedHardcore = response.optJSONObject("hardcoreWasEnabled")
                        ManualPatchExecutionResult(
                            success = response.optBoolean("success", false),
                            needsPpssppSafGrant = response.optBoolean("needsPpssppSafGrant", false),
                            message = response.optString("message").takeIf { it.isNotBlank() }
                                ?: context.getString(R.string.manual_patching_shizuku_service_no_response),
                            hardcoreWasEnabled = Emulator.SHIZUKU_MANAGED
                                .filter { support.isEnabled(it) }
                                .associateWith { emulator ->
                                    detectedHardcore?.optBoolean(requireConfigOverride(emulator).shizukuKey, false) == true
                                }
                        )
                    }.getOrElse {
                        Log.e(TAG, "Shizuku client call failed for action=$action", it)
                        ManualPatchExecutionResult(
                            success = false,
                            message = context.getString(R.string.manual_patching_shizuku_service_error, it.message ?: "Unknown error")
                        )
                    }

                    finish(result, this)
                }.start()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                finish(
                    ManualPatchExecutionResult(
                        success = false,
                        message = context.getString(R.string.manual_patching_shizuku_service_disconnected)
                    )
                )
            }
        }

        continuation.invokeOnCancellation {
            runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
        }

        runCatching {
            Shizuku.bindUserService(userServiceArgs, connection)
        }.onFailure {
            finish(
                ManualPatchExecutionResult(
                    success = false,
                    message = context.getString(R.string.manual_patching_shizuku_service_error, it.message ?: "Unknown error")
                )
            )
        }
    }
}

internal fun saveShizukuHardcoreWasEnabled(context: Context, flags: Map<Emulator, Boolean>) {
    context.getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE).edit {
        flags.forEach { (emulator, wasEnabled) ->
            putBoolean(requireConfigOverride(emulator).hardcoreWasEnabledPrefsKey, wasEnabled)
        }
    }
}

internal fun loadShizukuHardcoreWasEnabled(context: Context): Map<Emulator, Boolean> {
    val prefs = context.getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE)
    return Emulator.SHIZUKU_MANAGED.associateWith { emulator ->
        prefs.getBoolean(requireConfigOverride(emulator).hardcoreWasEnabledPrefsKey, false)
    }
}

internal fun clearShizukuHardcoreWasEnabled(context: Context) {
    context.getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE).edit {
        Emulator.SHIZUKU_MANAGED.forEach {
            remove(requireConfigOverride(it).hardcoreWasEnabledPrefsKey)
        }
    }
}

class ShizukuManualPatcherService() : IShizukuManualPatcher.Stub() {
    private var context: Context? = null

    @Keep
    @Suppress("unused")
    constructor(context: Context) : this() {
        this.context = context.applicationContext
    }

    override fun destroy() {
        Log.i(TAG, "Shizuku user service destroy")
        System.exit(0)
    }

    override fun execute(requestJson: String): String {
        Log.i(TAG, "Shizuku user service execute request=$requestJson")
        return runCatching {
            val request = JSONObject(requestJson)
            val action = request.getString("action")
            val proxyAddress = request.getString("proxyAddress")
            val ppssppRootMode = request.optString("ppssppRootMode")
                .takeIf { it.isNotBlank() }
                ?.let { stored -> PrefsConstants.PpssppRootMode.entries.firstOrNull { it.name == stored } }
                ?: PrefsConstants.PpssppRootMode.Unknown
            val ppssppIniPath = request.optString("ppssppIniPath").takeIf { it.isNotBlank() }
            val enabledEmulators = request.getJSONArray("enabledEmulators")
            val requestedHardcore = request.optJSONObject("hardcoreWasEnabled")
            val messages = mutableListOf<String>()
            val detectedHardcore = JSONObject()

            for (index in 0 until enabledEmulators.length()) {
                val requestedKey = enabledEmulators.getString(index)
                val emulator = Emulator.SHIZUKU_MANAGED.firstOrNull {
                    requireConfigOverride(it).shizukuKey == requestedKey
                }
                emulator?.let { forceStopPackages(it.packageCandidates) }
                val restoreHardcore = requestedHardcore?.optBoolean(requestedKey, false) == true
                val detectHardcore = emulator?.let { requireConfigOverride(it).detectHardcoreEnabled }
                    ?: { false }

                val result = when (emulator) {
                    Emulator.RetroArch -> {
                        transformFirstExistingFile(
                            candidates = RETROARCH_SOURCE_CANDIDATES,
                            patchMessage = "RetroArch patched successfully.",
                            revertMessage = "RetroArch reverted successfully.",
                            unavailableMessage = if (action == SHIZUKU_ACTION_PATCH) {
                                "Could not patch RetroArch automatically."
                            } else {
                                "Could not revert RetroArch automatically."
                            },
                            action = action,
                            detectHardcore = detectHardcore,
                            transform = {
                                if (action == SHIZUKU_ACTION_PATCH) buildPatchedContent(it, proxyAddress)
                                else buildRevertedContent(it, restoreHardcore)
                            }
                        )
                    }

                    Emulator.Dolphin -> {
                        val globalResult = transformFirstExistingFile(
                            candidates = DOLPHIN_SHIZUKU_SOURCE_CANDIDATES,
                            patchMessage = "Dolphin patched successfully.",
                            revertMessage = "Dolphin reverted successfully.",
                            unavailableMessage = if (action == SHIZUKU_ACTION_PATCH) {
                                "Could not patch Dolphin automatically."
                            } else {
                                "Could not revert Dolphin automatically."
                            },
                            action = action,
                            detectHardcore = detectHardcore,
                            transform = {
                                if (action == SHIZUKU_ACTION_PATCH) buildPatchedDolphinContent(it, proxyAddress)
                                else buildRevertedDolphinContent(it, restoreHardcore)
                            }
                        )
                        if (!globalResult.success) {
                            globalResult
                        } else if (action == SHIZUKU_ACTION_PATCH) {
                            val directory = DOLPHIN_GAME_SETTINGS_SOURCE_CANDIDATES
                                .asSequence()
                                .map(::File)
                                .firstOrNull(File::isDirectory)
                            if (directory == null) {
                                globalResult
                            } else {
                                val overrideResult = patchDolphinGameSettingsViaFile(directory)
                                if (!overrideResult.success) {
                                    ServiceFileResult(success = false, message = overrideResult.message ?: globalResult.message)
                                } else {
                                    val serviceContext = context
                                        ?: return JSONObject()
                                            .put("success", false)
                                            .put("message", "Shizuku service context unavailable.")
                                            .toString()
                                    persistDolphinGameSettingsOverrides(serviceContext, overrideResult.overrides)
                                    globalResult
                                }
                            }
                        } else {
                            val serviceContext = context
                                ?: return JSONObject()
                                    .put("success", false)
                                    .put("message", "Shizuku service context unavailable.")
                                    .toString()
                            val overrideResult = revertDolphinGameSettingsHardcoreOverrides(serviceContext, null)
                            if (!overrideResult.success) {
                                ServiceFileResult(success = false, message = overrideResult.message ?: globalResult.message)
                            } else {
                                globalResult
                            }
                        }
                    }

                    Emulator.Ppsspp -> {
                        val resolvedPpssppPath = resolvePpssppIniPathForService(ppssppIniPath, ppssppRootMode)
                        if (action == SHIZUKU_ACTION_PATCH && resolvedPpssppPath == null) {
                            return JSONObject()
                                .put("success", false)
                                .put("needsPpssppSafGrant", true)
                                .put("message", "Could not patch PPSSPP automatically.")
                                .toString()
                        }
                        Log.i(TAG, "PPSSPP resolved path=$resolvedPpssppPath requestPath=$ppssppIniPath")
                        transformFile(
                            path = resolvedPpssppPath,
                            patchMessage = "PPSSPP patched successfully.",
                            revertMessage = "PPSSPP reverted successfully.",
                            unavailableMessage = if (action == SHIZUKU_ACTION_PATCH) {
                                "Could not patch PPSSPP automatically."
                            } else {
                                "Could not revert PPSSPP automatically."
                            },
                            action = action,
                            detectHardcore = detectHardcore,
                            transform = {
                                if (action == SHIZUKU_ACTION_PATCH) buildPatchedPpssppContent(it, proxyAddress)
                                else buildRevertedPpssppContent(it, restoreHardcore)
                            }
                        )
                    }

                    else -> ServiceFileResult(success = false, message = "Unknown emulator requested.")
                }

                if (!result.success) {
                    return JSONObject()
                        .put("success", false)
                        .put("message", result.message)
                        .toString()
                }

                messages += result.message
                detectedHardcore.put(requestedKey, result.hardcoreWasEnabled)
            }

            val response = JSONObject()
                .put("success", true)
                .put("message", messages.joinToString("\n"))
                .put("hardcoreWasEnabled", detectedHardcore)
                .toString()
            Log.i(TAG, "Shizuku user service success action=$action")
            response
        }.getOrElse {
            Log.e(TAG, "Shizuku user service failed", it)
            JSONObject()
                .put("success", false)
                .put("message", it.message ?: "Unknown error")
                .toString()
        }
    }
}

private data class ServiceFileResult(
    val success: Boolean,
    val message: String,
    // Hardcore state read off the file *before* patching, so the app can persist it and ask for
    // it back on revert. Meaningless for the revert action itself.
    val hardcoreWasEnabled: Boolean = false
)

private fun transformFirstExistingFile(
    candidates: List<String>,
    patchMessage: String,
    revertMessage: String,
    unavailableMessage: String,
    action: String,
    detectHardcore: (String) -> Boolean,
    transform: (String) -> String
): ServiceFileResult {
    val target = candidates.asSequence().map(::File).firstOrNull(File::exists)
        ?: return ServiceFileResult(success = false, message = unavailableMessage)

    return transformTarget(target, patchMessage, revertMessage, unavailableMessage, action, detectHardcore, transform)
}

private fun transformFile(
    path: String?,
    patchMessage: String,
    revertMessage: String,
    unavailableMessage: String,
    action: String,
    detectHardcore: (String) -> Boolean,
    transform: (String) -> String
): ServiceFileResult {
    val target = path?.let(::File)?.takeIf(File::exists)
        ?: return ServiceFileResult(success = false, message = unavailableMessage)

    return transformTarget(target, patchMessage, revertMessage, unavailableMessage, action, detectHardcore, transform)
}

private fun transformTarget(
    target: File,
    patchMessage: String,
    revertMessage: String,
    unavailableMessage: String,
    action: String,
    detectHardcore: (String) -> Boolean,
    transform: (String) -> String
): ServiceFileResult = runCatching {
    val original = target.readText()
    val hardcoreWasEnabled = action == SHIZUKU_ACTION_PATCH && detectHardcore(original)
    val updated = transform(original)
    if (updated != original) {
        target.writeText(updated)
    }

    ServiceFileResult(
        success = true,
        message = if (action == SHIZUKU_ACTION_PATCH) patchMessage else revertMessage,
        hardcoreWasEnabled = hardcoreWasEnabled
    )
}.getOrElse {
    ServiceFileResult(success = false, message = it.message ?: unavailableMessage)
}

private fun resolvePpssppIniPathForShizuku(context: Context): String? {
    PrefsConstants.loadPpssppSafUri(context)
        ?: return null
    return treeUriToAbsoluteStoragePath(PrefsConstants.loadPpssppSafUri(context) ?: return null)
}

internal fun resolvePpssppIniPathForService(
    rootPath: String?,
    rootMode: PrefsConstants.PpssppRootMode,
    fileExists: (String) -> Boolean = ::shellFileExists
): String? {
    val candidates = ppssppIniPathCandidates(rootPath, rootMode)

    val resolved = candidates.firstOrNull(fileExists)
    Log.i(TAG, "PPSSPP candidate paths=${candidates.joinToString()} resolved=$resolved")
    return resolved
}

internal fun ppssppIniPathCandidates(
    rootPath: String?,
    rootMode: PrefsConstants.PpssppRootMode
): List<String> {
    val defaultPaths = UI_PPSSPP_PACKAGE_CANDIDATES.map { packageName ->
        "/storage/emulated/0/Android/data/$packageName/files/$PPSSPP_PSP_DIR/$PPSSPP_SYSTEM_DIR/$PPSSPP_INI_FILE"
    }
    return buildList {
        when (rootMode) {
            PrefsConstants.PpssppRootMode.CustomRoot -> {
                listOfNotNull(rootPath).forEach { candidateRoot ->
                    add("$candidateRoot/$PPSSPP_SYSTEM_DIR/$PPSSPP_INI_FILE")
                    add("$candidateRoot/$PPSSPP_PSP_DIR/$PPSSPP_SYSTEM_DIR/$PPSSPP_INI_FILE")
                }
            }

            PrefsConstants.PpssppRootMode.DefaultPackagePath,
            PrefsConstants.PpssppRootMode.Unknown -> addAll(defaultPaths)
        }
    }
}

private fun shellFileExists(path: String): Boolean =
    runShellCommand("test -f \"$path\"").exitCode == 0

private fun runShellCommand(command: String): ShellCommandResult {
    val process = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", command))
    val stdout = process.inputStream.bufferedReader().use { it.readText() }
    val stderr = process.errorStream.bufferedReader().use { it.readText() }
    val exitCode = process.waitFor()
    if (exitCode != 0) {
        Log.w(TAG, "Shell command failed exitCode=$exitCode command=$command stderr=$stderr")
    }
    return ShellCommandResult(exitCode = exitCode, stdout = stdout, stderr = stderr)
}

private data class ShellCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)

private fun treeUriToAbsoluteStoragePath(treeUri: Uri): String {
    val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        .takeIf { it.isNotBlank() }
        ?: return "/storage/emulated/0"
    return documentIdToAbsoluteStoragePath(treeDocumentId)
}

private fun documentIdToAbsoluteStoragePath(documentId: String): String {
    val volume = documentId.substringBefore(':', missingDelimiterValue = "")
        .takeIf { it.isNotBlank() }
        ?: return "/storage/emulated/0"
    val relativePath = documentId.substringAfter(':', missingDelimiterValue = "")
        .trim('/')

    val storageRoot = if (volume.equals("primary", ignoreCase = true)) {
        "/storage/emulated/0"
    } else {
        "/storage/$volume"
    }

    return if (relativePath.isBlank()) {
        storageRoot
    } else {
        "$storageRoot/$relativePath"
    }
}

private fun forceStopPackages(packageNames: List<String>) {
    packageNames.forEach { packageName ->
        runCatching {
            Runtime.getRuntime().exec(arrayOf("/system/bin/am", "force-stop", packageName)).waitFor()
        }
    }
}
