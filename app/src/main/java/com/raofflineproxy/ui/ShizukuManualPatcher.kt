package com.raofflineproxy.ui

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.provider.DocumentsContract
import android.util.Log
import androidx.annotation.Keep
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
private const val SHIZUKU_ACTION_REVERT = "revert"
private const val SHIZUKU_SERVICE_TAG = "manual_emulator_patcher"
private const val TAG = "RAProxy/ShizukuManual"

internal suspend fun executeShizukuManualPatch(
    context: Context,
    support: EmulatorSupport,
    action: String
): ManualPatchExecutionResult {
    if (!canUseShizuku(context)) {
        return ManualPatchExecutionResult(success = false, message = shizukuStatusLabel(context, resolveShizukuStatus(context)))
    }

    if (!support.hasAnyEnabled) {
        return ManualPatchExecutionResult(success = false, message = context.getString(R.string.proxy_start_requires_emulator))
    }

    val ppssppIniPath = if (support.ppssppEnabled) {
        resolvePpssppIniPathForShizuku(context)
    } else {
        null
    }

    val requestJson = JSONObject()
        .put("action", action)
        .put("proxyAddress", proxyValue(context))
        .put("ppssppIniPath", ppssppIniPath)
        .put("enabledEmulators", JSONArray().apply {
            if (support.retroArchEnabled) put("retroarch")
            if (support.dolphinEnabled) put("dolphin")
            if (support.ppssppEnabled) put("ppsspp")
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
                        ManualPatchExecutionResult(
                            success = response.optBoolean("success", false),
                            message = response.optString("message").takeIf { it.isNotBlank() }
                                ?: context.getString(R.string.manual_patching_shizuku_service_no_response)
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

class ShizukuManualPatcherService() : IShizukuManualPatcher.Stub() {
    private var context: Context? = null

    @Keep
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
            val ppssppIniPath = request.optString("ppssppIniPath").takeIf { it.isNotBlank() }
            val enabledEmulators = request.getJSONArray("enabledEmulators")
            val messages = mutableListOf<String>()

            for (index in 0 until enabledEmulators.length()) {
                val result = when (enabledEmulators.getString(index)) {
                    "retroarch" -> {
                        forceStopPackages(RETROARCH_PACKAGE_CANDIDATES)
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
                            transform = {
                                if (action == SHIZUKU_ACTION_PATCH) buildPatchedContent(it, proxyAddress)
                                else buildRevertedContent(it, restoreHardcore = false)
                            }
                        )
                    }

                    "dolphin" -> {
                        forceStopPackages(DOLPHIN_PACKAGE_CANDIDATES)
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
                            transform = {
                                if (action == SHIZUKU_ACTION_PATCH) buildPatchedDolphinContent(it, proxyAddress)
                                else buildRevertedDolphinContent(it, restoreHardcore = false)
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

                    "ppsspp" -> {
                        forceStopPackages(listOf(UI_PPSSPP_PACKAGE))
                        val resolvedPpssppPath = resolvePpssppIniPathForService(ppssppIniPath)
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
                            transform = {
                                if (action == SHIZUKU_ACTION_PATCH) buildPatchedPpssppContent(it, proxyAddress)
                                else buildRevertedPpssppContent(it, restoreHardcore = false)
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
            }

            val response = JSONObject()
                .put("success", true)
                .put("message", messages.joinToString("\n"))
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
    val message: String
)

private fun transformFirstExistingFile(
    candidates: List<String>,
    patchMessage: String,
    revertMessage: String,
    unavailableMessage: String,
    action: String,
    transform: (String) -> String
): ServiceFileResult {
    val target = candidates.asSequence().map(::File).firstOrNull(File::exists)
        ?: return ServiceFileResult(success = false, message = unavailableMessage)

    return runCatching {
        val original = target.readText()
        val updated = transform(original)
        if (updated != original) {
            target.writeText(updated)
        }

        ServiceFileResult(
            success = true,
            message = if (action == SHIZUKU_ACTION_PATCH) patchMessage else revertMessage
        )
    }.getOrElse {
        ServiceFileResult(success = false, message = it.message ?: unavailableMessage)
    }
}

private fun transformFile(
    path: String?,
    patchMessage: String,
    revertMessage: String,
    unavailableMessage: String,
    action: String,
    transform: (String) -> String
): ServiceFileResult {
    val target = path?.let(::File)?.takeIf(File::exists)
        ?: return ServiceFileResult(success = false, message = unavailableMessage)

    return runCatching {
        val original = target.readText()
        val updated = transform(original)
        if (updated != original) {
            target.writeText(updated)
        }

        ServiceFileResult(
            success = true,
            message = if (action == SHIZUKU_ACTION_PATCH) patchMessage else revertMessage
        )
    }.getOrElse {
        ServiceFileResult(success = false, message = it.message ?: unavailableMessage)
    }
}

private fun resolvePpssppIniPathForShizuku(context: Context): String? {
    PrefsConstants.loadPpssppSafUri(context)
        ?: return null
    return treeUriToAbsoluteStoragePath(PrefsConstants.loadPpssppSafUri(context) ?: return null)
}

private fun resolvePpssppIniPathForService(rootPath: String?): String {
    val defaultPath = "/storage/emulated/0/Android/data/$UI_PPSSPP_PACKAGE/files/$PPSSPP_PSP_DIR/$PPSSPP_SYSTEM_DIR/$PPSSPP_INI_FILE"
    val rootCandidates = listOfNotNull(rootPath)
    val candidates = buildList {
        add(defaultPath)
        rootCandidates.forEach { candidateRoot ->
            add("$candidateRoot/$PPSSPP_SYSTEM_DIR/$PPSSPP_INI_FILE")
            add("$candidateRoot/$PPSSPP_PSP_DIR/$PPSSPP_SYSTEM_DIR/$PPSSPP_INI_FILE")
        }
    }

    val resolved = candidates.firstOrNull { shellFileExists(it) } ?: defaultPath
    Log.i(TAG, "PPSSPP candidate paths=${candidates.joinToString()} resolved=$resolved")
    return resolved
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
