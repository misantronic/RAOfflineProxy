package com.raofflineproxy.ui

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.AtomicFile
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.raofflineproxy.PrefsConstants
import com.raofflineproxy.R
import com.raofflineproxy.proxy.LoginCredentials
import com.raofflineproxy.proxyValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private val DOLPHIN_EXT_STORAGE by lazy { Environment.getExternalStorageDirectory().path }
private const val TAG = "RAProxy/DolphinCfg"
private const val DOLPHIN_CFG_BACKUP_NAME = "RetroAchievements.raofflineproxy.ini"
private const val DOLPHIN_GAME_SETTINGS_RELATIVE_PATH = "GameSettings"
private const val DOLPHIN_GAME_SETTINGS_SECTION = "Achievements.Achievements"
private const val DOLPHIN_GAME_SETTINGS_KEY = "HardcoreEnabled"

internal val DOLPHIN_PACKAGE_CANDIDATES = listOf(
    "org.dolphinemu.dolphinemu",
    "org.dolphinemu.dolphinemu.beta",
    "org.dolphinemu.dolphinemu.debug"
)
private const val DOLPHIN_CFG_RELATIVE_PATH = "Config/RetroAchievements.ini"

private val DOLPHIN_SOURCE_CANDIDATES by lazy {
    DOLPHIN_PACKAGE_CANDIDATES.flatMap { packageName ->
        listOf(
            "$DOLPHIN_EXT_STORAGE/Android/data/$packageName/files/$DOLPHIN_CFG_RELATIVE_PATH",
            "/storage/emulated/0/Android/data/$packageName/files/$DOLPHIN_CFG_RELATIVE_PATH"
        )
    } + listOf(
        "$DOLPHIN_EXT_STORAGE/dolphin-emu/$DOLPHIN_CFG_RELATIVE_PATH",
        "/storage/emulated/0/dolphin-emu/$DOLPHIN_CFG_RELATIVE_PATH"
    )
}

private val DOLPHIN_SAF_CFG_PATHS = DOLPHIN_PACKAGE_CANDIDATES.map { packageName ->
    listOf(packageName, "files", "Config", "RetroAchievements.ini")
} + listOf(
    listOf("files", "Config", "RetroAchievements.ini"),
    listOf("Config", "RetroAchievements.ini"),
    listOf("RetroAchievements.ini")
)

private val DOLPHIN_GAME_SETTINGS_SOURCE_CANDIDATES by lazy {
    DOLPHIN_PACKAGE_CANDIDATES.flatMap { packageName ->
        listOf(
            "$DOLPHIN_EXT_STORAGE/Android/data/$packageName/files/$DOLPHIN_GAME_SETTINGS_RELATIVE_PATH",
            "/storage/emulated/0/Android/data/$packageName/files/$DOLPHIN_GAME_SETTINGS_RELATIVE_PATH"
        )
    }
}

private val DOLPHIN_SAF_GAME_SETTINGS_PATHS = DOLPHIN_PACKAGE_CANDIDATES.map { packageName ->
    listOf(packageName, "files", DOLPHIN_GAME_SETTINGS_RELATIVE_PATH)
} + listOf(
    listOf("files", DOLPHIN_GAME_SETTINGS_RELATIVE_PATH),
    listOf(DOLPHIN_GAME_SETTINGS_RELATIVE_PATH)
)

data class DolphinPatchResult(
    val success: Boolean,
    val message: String,
    val needsSafGrant: Boolean = false,
    val invalidSafGrant: Boolean = false,
    val copyBackPath: String? = null,
    val hardcoreWasEnabled: Boolean = false,
    val credentials: ImportedCredentials? = null,
    val skippedNotInstalled: Boolean = false
)

internal data class DolphinGameSettingsOverride(
    val relativePath: String,
    val originalValue: String
)

private data class DolphinIniEntry(
    val lineIndex: Int,
    val prefix: String,
    val value: String
)

internal data class DolphinGameSettingsUpdate(
    val content: String,
    val originalValue: String
)

private data class DolphinGameSettingsPatchOutcome(
    val success: Boolean,
    val message: String? = null,
    val overrides: List<DolphinGameSettingsOverride> = emptyList()
)

private data class DolphinStrings(
    val noOpMessage: Int,
    val successSaf: Int,
    val errorSaf: Int,
    val successFile: Int,
    val errorFile: Int,
    val configMissingInFolder: Int,
    val unavailableError: Int
)

private val DOLPHIN_PATCH_STRINGS = DolphinStrings(
    noOpMessage = R.string.dolphin_patch_already_configured,
    successSaf = R.string.dolphin_patch_success_saf,
    errorSaf = R.string.dolphin_patch_error_saf,
    successFile = R.string.dolphin_patch_success,
    errorFile = R.string.dolphin_patch_error_file,
    configMissingInFolder = R.string.dolphin_patch_cfg_not_in_folder,
    unavailableError = R.string.dolphin_patch_error_unavailable
)

private val DOLPHIN_REVERT_STRINGS = DolphinStrings(
    noOpMessage = R.string.dolphin_revert_already_reverted,
    successSaf = R.string.dolphin_revert_success_saf,
    errorSaf = R.string.dolphin_revert_error_saf,
    successFile = R.string.dolphin_revert_success,
    errorFile = R.string.dolphin_revert_error_file,
    configMissingInFolder = R.string.dolphin_patch_cfg_not_in_folder,
    unavailableError = R.string.dolphin_revert_error_unavailable
)

internal fun isDolphinInstalled(context: Context): Boolean =
    DOLPHIN_PACKAGE_CANDIDATES.any { packageName ->
        runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess
    } || DOLPHIN_SOURCE_CANDIDATES.any { File(it).exists() }

internal fun canPatchDolphinCfgDirectly(): Boolean {
    val directCandidate = DOLPHIN_SOURCE_CANDIDATES
        .asSequence()
        .map(::File)
        .firstOrNull(File::exists)

    return directCandidate?.canWrite() == true
}

fun patchDolphinCfg(
    context: Context,
    treeUri: Uri?,
    storedCredentials: LoginCredentials? = null
): DolphinPatchResult {
    if (!isDolphinInstalled(context)) {
        Log.i(TAG, "patch: Dolphin not installed")
        return DolphinPatchResult(success = true, message = "Dolphin not installed.", skippedNotInstalled = true)
    }

    Log.i(TAG, "patch: starting treeUri=$treeUri proxy=${proxyValue(context)}")

    val transform: (String) -> String = {
        buildPatchedDolphinContent(
            content = it,
            proxyAddress = proxyValue(context),
            storedCredentials = storedCredentials
        )
    }
    val globalResult = applyDolphinTransform(
        context = context,
        treeUri = treeUri,
        transform = transform,
        strings = DOLPHIN_PATCH_STRINGS,
        detectHardcore = true,
        ensureBackup = true
    )

    if (!globalResult.success) {
        return globalResult
    }

    val gameSettingsResult = patchDolphinGameSettingsHardcoreOverrides(context, treeUri)
    if (!gameSettingsResult.success) {
        applyDolphinTransform(
            context = context,
            treeUri = treeUri,
            transform = { buildRevertedDolphinContent(it, globalResult.hardcoreWasEnabled) },
            strings = DOLPHIN_REVERT_STRINGS,
            detectHardcore = false,
            ensureBackup = false
        )
        return globalResult.copy(
            success = false,
            message = gameSettingsResult.message ?: globalResult.message
        )
    }

    persistDolphinGameSettingsOverrides(context, gameSettingsResult.overrides)
    return globalResult
}

fun revertDolphinCfg(context: Context, treeUri: Uri?, restoreHardcore: Boolean = false): DolphinPatchResult {
    if (!isDolphinInstalled(context)) {
        Log.i(TAG, "revert: Dolphin not installed")
        return DolphinPatchResult(success = true, message = "Dolphin not installed.", skippedNotInstalled = true)
    }

    Log.i(TAG, "revert: starting treeUri=$treeUri restoreHardcore=$restoreHardcore")

    val transform: (String) -> String = { buildRevertedDolphinContent(it, restoreHardcore) }
    val globalResult = applyDolphinTransform(
        context = context,
        treeUri = treeUri,
        transform = transform,
        strings = DOLPHIN_REVERT_STRINGS,
        detectHardcore = false,
        ensureBackup = false
    )

    val gameSettingsResult = revertDolphinGameSettingsHardcoreOverrides(context, treeUri)
    if (!gameSettingsResult.success) {
        return globalResult.copy(
            success = false,
            message = gameSettingsResult.message ?: globalResult.message
        )
    }

    return globalResult
}

private fun patchDolphinGameSettingsHardcoreOverrides(
    context: Context,
    treeUri: Uri?
): DolphinGameSettingsPatchOutcome {
    if (treeUri != null) {
        val safResult = patchDolphinGameSettingsViaSaf(context, treeUri)
        if (safResult != null) {
            return safResult
        }
    }

    val directDirectory = DOLPHIN_GAME_SETTINGS_SOURCE_CANDIDATES
        .asSequence()
        .map(::File)
        .firstOrNull { it.isDirectory && it.canRead() && it.canWrite() }

    if (directDirectory != null) {
        return patchDolphinGameSettingsViaFile(directDirectory)
    }

    return DolphinGameSettingsPatchOutcome(success = true)
}

private fun revertDolphinGameSettingsHardcoreOverrides(
    context: Context,
    treeUri: Uri?
): DolphinGameSettingsPatchOutcome {
    val overrides = loadPersistedDolphinGameSettingsOverrides(context)
    if (overrides.isEmpty()) {
        return DolphinGameSettingsPatchOutcome(success = true)
    }

    val pendingOverrides = overrides.toMutableList()
    val failedOverrides = mutableListOf<DolphinGameSettingsOverride>()

    if (treeUri != null) {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
        if (tree != null) {
            val stillPending = mutableListOf<DolphinGameSettingsOverride>()
            pendingOverrides.forEach { trackedOverride ->
                val resolution = resolveDolphinGameSettingsDocument(tree, trackedOverride.relativePath)
                if (resolution == null) {
                    stillPending += trackedOverride
                } else if (!revertDolphinGameSettingsDocument(context, resolution, trackedOverride)) {
                    failedOverrides += trackedOverride
                }
            }
            pendingOverrides.clear()
            pendingOverrides += stillPending
        }
    }

    pendingOverrides.forEach { trackedOverride ->
        val file = resolveDolphinGameSettingsFile(trackedOverride.relativePath)
        if (file != null && !revertDolphinGameSettingsFile(file, trackedOverride)) {
            failedOverrides += trackedOverride
        }
    }

    if (failedOverrides.isNotEmpty()) {
        persistDolphinGameSettingsOverrides(context, failedOverrides.distinctBy(DolphinGameSettingsOverride::relativePath))
        return DolphinGameSettingsPatchOutcome(
            success = false,
            message = context.getString(R.string.dolphin_revert_error_file, DOLPHIN_GAME_SETTINGS_RELATIVE_PATH, "Could not restore Dolphin per-game hardcore overrides")
        )
    }

    PrefsConstants.clearDolphinGameSettingsHardcoreOverrides(context)
    return DolphinGameSettingsPatchOutcome(success = true)
}

private fun patchDolphinGameSettingsViaSaf(
    context: Context,
    treeUri: Uri
): DolphinGameSettingsPatchOutcome? {
    val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return null
    val directory = DOLPHIN_SAF_GAME_SETTINGS_PATHS.firstNotNullOfOrNull { segments ->
        segments.fold(tree as DocumentFile?) { current, segment -> current?.findFile(segment) }
            ?.takeIf { it.exists() && it.isDirectory }
    } ?: return DolphinGameSettingsPatchOutcome(success = true)

    val changedFiles = mutableListOf<Pair<DocumentFile, DolphinGameSettingsOverride>>()
    val overrides = mutableListOf<DolphinGameSettingsOverride>()
    val iniFiles = directory.listFiles()
        .filter { it.isFile && (it.name?.endsWith(".ini", ignoreCase = true) == true) }

    try {
        iniFiles.forEach { document ->
            val original = context.contentResolver.openInputStream(document.uri)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: return DolphinGameSettingsPatchOutcome(success = false, message = context.getString(R.string.patch_could_not_read, document.name))
            val update = buildPatchedDolphinGameSettingsContent(original) ?: return@forEach
            context.contentResolver.openOutputStream(document.uri, "wt")
                ?.use { it.write(update.content.toByteArray()) }
                ?: return DolphinGameSettingsPatchOutcome(success = false, message = context.getString(R.string.patch_could_not_write, document.name))
            val relativePath = "$DOLPHIN_GAME_SETTINGS_RELATIVE_PATH/${document.name}"
            val trackedOverride = DolphinGameSettingsOverride(relativePath = relativePath, originalValue = update.originalValue)
            changedFiles += document to trackedOverride
            overrides += trackedOverride
        }
    } catch (e: Exception) {
        changedFiles.forEach { (document, trackedOverride) ->
            runCatching {
                val current = context.contentResolver.openInputStream(document.uri)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: return@runCatching
                val reverted = buildRevertedDolphinGameSettingsContent(current, trackedOverride.originalValue) ?: return@runCatching
                context.contentResolver.openOutputStream(document.uri, "wt")
                    ?.use { it.write(reverted.toByteArray()) }
            }
        }
        return DolphinGameSettingsPatchOutcome(success = false, message = context.getString(R.string.dolphin_patch_error_saf, e.message))
    }

    return DolphinGameSettingsPatchOutcome(success = true, overrides = overrides)
}

private fun patchDolphinGameSettingsViaFile(directory: File): DolphinGameSettingsPatchOutcome {
    val directoryPath = directory.path
    val changedFiles = mutableListOf<Pair<File, DolphinGameSettingsOverride>>()
    val overrides = mutableListOf<DolphinGameSettingsOverride>()
    val iniFiles = directory.listFiles()
        ?.filter { it.isFile && it.name.endsWith(".ini", ignoreCase = true) }
        .orEmpty()

    try {
        iniFiles.forEach { file ->
            val original = file.readText()
            val update = buildPatchedDolphinGameSettingsContent(original) ?: return@forEach
            writeDolphinFileAtomically(file, update.content)
            val relativePath = "$DOLPHIN_GAME_SETTINGS_RELATIVE_PATH/${file.name}"
            val trackedOverride = DolphinGameSettingsOverride(relativePath = relativePath, originalValue = update.originalValue)
            changedFiles += file to trackedOverride
            overrides += trackedOverride
        }
    } catch (e: Exception) {
        changedFiles.forEach { (file, trackedOverride) ->
            runCatching {
                val current = file.readText()
                val reverted = buildRevertedDolphinGameSettingsContent(current, trackedOverride.originalValue) ?: return@runCatching
                writeDolphinFileAtomically(file, reverted)
            }
        }
        return DolphinGameSettingsPatchOutcome(
            success = false,
            message = "Error patching Dolphin game settings $directoryPath: ${e.message}"
        )
    }

    return DolphinGameSettingsPatchOutcome(success = true, overrides = overrides)
}

private fun resolveDolphinGameSettingsDocument(root: DocumentFile, relativePath: String): DocumentFile? {
    val segments = relativePath.split('/').filter(String::isNotBlank)
    return DOLPHIN_SAF_GAME_SETTINGS_PATHS.firstNotNullOfOrNull { baseSegments ->
        (baseSegments + segments.drop(1)).fold(root as DocumentFile?) { current, segment -> current?.findFile(segment) }
            ?.takeIf { it.exists() && it.isFile }
    }
}

private fun resolveDolphinGameSettingsFile(relativePath: String): File? {
    val fileName = relativePath.substringAfterLast('/')
    return DOLPHIN_GAME_SETTINGS_SOURCE_CANDIDATES
        .asSequence()
        .map { File(it, fileName) }
        .firstOrNull { it.isFile && it.canRead() && it.canWrite() }
}

private fun revertDolphinGameSettingsDocument(
    context: Context,
    document: DocumentFile,
    trackedOverride: DolphinGameSettingsOverride
): Boolean = runCatching {
    val current = context.contentResolver.openInputStream(document.uri)
        ?.bufferedReader()
        ?.use { it.readText() }
        ?: return false
    val reverted = buildRevertedDolphinGameSettingsContent(current, trackedOverride.originalValue) ?: return true
    context.contentResolver.openOutputStream(document.uri, "wt")
        ?.use { it.write(reverted.toByteArray()) }
        ?: return false
    true
}.getOrDefault(false)

private fun revertDolphinGameSettingsFile(
    file: File,
    trackedOverride: DolphinGameSettingsOverride
): Boolean = runCatching {
    val current = file.readText()
    val reverted = buildRevertedDolphinGameSettingsContent(current, trackedOverride.originalValue) ?: return true
    writeDolphinFileAtomically(file, reverted)
    true
}.getOrDefault(false)

private fun persistDolphinGameSettingsOverrides(
    context: Context,
    overrides: List<DolphinGameSettingsOverride>
) {
    if (overrides.isEmpty()) {
        PrefsConstants.clearDolphinGameSettingsHardcoreOverrides(context)
        return
    }

    val encoded = JSONArray().apply {
        overrides.distinctBy(DolphinGameSettingsOverride::relativePath).forEach { trackedOverride ->
            put(
                JSONObject()
                    .put("relativePath", trackedOverride.relativePath)
                    .put("originalValue", trackedOverride.originalValue)
            )
        }
    }.toString()
    PrefsConstants.saveDolphinGameSettingsHardcoreOverrides(context, encoded)
}

private fun loadPersistedDolphinGameSettingsOverrides(context: Context): List<DolphinGameSettingsOverride> {
    val encoded = PrefsConstants.loadDolphinGameSettingsHardcoreOverrides(context)
        ?: return emptyList()

    return runCatching { JSONArray(encoded) }
        .getOrNull()
        ?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val relativePath = item.optString("relativePath").takeIf { it.isNotBlank() } ?: continue
                    val originalValue = item.optString("originalValue").takeIf { it.isNotBlank() } ?: continue
                    add(DolphinGameSettingsOverride(relativePath = relativePath, originalValue = originalValue))
                }
            }
        }
        .orEmpty()
}

private fun applyDolphinTransform(
    context: Context,
    treeUri: Uri?,
    transform: (String) -> String,
    strings: DolphinStrings,
    detectHardcore: Boolean,
    ensureBackup: Boolean
): DolphinPatchResult {
    Log.d(TAG, "apply: treeUri=$treeUri detectHardcore=$detectHardcore ensureBackup=$ensureBackup candidates=${DOLPHIN_SOURCE_CANDIDATES.size}")
    if (treeUri != null) {
        val safResult = transformDolphinViaSaf(context, treeUri, transform, strings, detectHardcore, ensureBackup)
        if (safResult != null) {
            Log.i(TAG, "apply: SAF result success=${safResult.success} needsSafGrant=${safResult.needsSafGrant} invalidSafGrant=${safResult.invalidSafGrant} copyBackPath=${safResult.copyBackPath}")
            return safResult
        }
        Log.d(TAG, "apply: treeUri present but SAF tree could not be opened")
    }

    val directCandidate = DOLPHIN_SOURCE_CANDIDATES.map(::File).firstOrNull { it.exists() }
    Log.d(TAG, "apply: directCandidate=${directCandidate?.path} writable=${directCandidate?.canWrite()}")
    if (directCandidate != null && directCandidate.canWrite()) {
        return transformDolphinViaFile(context, directCandidate, transform, strings, detectHardcore, ensureBackup)
    }

    if (directCandidate != null || treeUri == null) {
        Log.w(TAG, "apply: requesting SAF grant directCandidate=${directCandidate?.path} treeUri=$treeUri sdk=${Build.VERSION.SDK_INT}")
        return DolphinPatchResult(
            success = false,
            message = context.getString(R.string.dolphin_saf_dialog_message),
            needsSafGrant = true
        )
    }

    Log.w(TAG, "apply: automatic patching unavailable")
    return DolphinPatchResult(
        success = false,
        message = context.getString(strings.unavailableError)
    )
}

private fun transformDolphinViaSaf(
    context: Context,
    treeUri: Uri,
    transform: (String) -> String,
    strings: DolphinStrings,
    detectHardcore: Boolean,
    ensureBackup: Boolean
): DolphinPatchResult? {
    val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return null
    Log.d(TAG, "saf: opened tree uri=$treeUri name=${tree.name}")

    for (segments in DOLPHIN_SAF_CFG_PATHS) {
        Log.d(TAG, "saf: trying segments=${segments.joinToString("/")}")
        val cfgParent = segments.dropLast(1).fold(tree as DocumentFile?) { dir, seg -> dir?.findFile(seg) }
        val cfgFile = cfgParent?.findFile(segments.last())
        if (cfgFile == null || !cfgFile.exists()) {
            Log.d(TAG, "saf: not found segments=${segments.joinToString("/")}")
            continue
        }

        return try {
            val original = context.contentResolver.openInputStream(cfgFile.uri)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: return DolphinPatchResult(success = false, message = context.getString(R.string.patch_could_not_read, cfgFile.name))
            Log.i(TAG, "saf: found cfg uri=${cfgFile.uri} size=${original.length}")

            if (ensureBackup) {
                ensureDolphinSafBackupExists(context, cfgParent, original)
                    ?: return DolphinPatchResult(success = false, message = context.getString(strings.errorSaf, "Could not create $DOLPHIN_CFG_BACKUP_NAME"))
                Log.d(TAG, "saf: ensured backup $DOLPHIN_CFG_BACKUP_NAME")
            }

            val hardcoreWas = if (detectHardcore) detectDolphinHardcoreEnabled(original) else false
            val credentials = extractDolphinCredentials(original)
            val transformed = transform(original)
            Log.d(TAG, "saf: transform changed=${transformed != original} hardcoreWas=$hardcoreWas")
            if (transformed == original) {
                DolphinPatchResult(success = true, message = context.getString(strings.noOpMessage), hardcoreWasEnabled = hardcoreWas, credentials = credentials)
            } else {
                context.contentResolver.openOutputStream(cfgFile.uri, "wt")
                    ?.use { it.write(transformed.toByteArray()) }
                    ?: return DolphinPatchResult(success = false, message = context.getString(R.string.patch_could_not_write, cfgFile.name))
                Log.i(TAG, "saf: wrote updated config uri=${cfgFile.uri}")
                DolphinPatchResult(success = true, message = context.getString(strings.successSaf), hardcoreWasEnabled = hardcoreWas, credentials = credentials)
            }
        } catch (e: Exception) {
            Log.w(TAG, "saf: error ${e.message}", e)
            DolphinPatchResult(success = false, message = context.getString(strings.errorSaf, e.message))
        }
    }

    Log.w(TAG, "saf: config not found in granted tree")
    return DolphinPatchResult(
        success = false,
        message = context.getString(strings.configMissingInFolder),
        invalidSafGrant = true
    )
}

private fun transformDolphinViaFile(
    context: Context,
    target: File,
    transform: (String) -> String,
    strings: DolphinStrings,
    detectHardcore: Boolean,
    ensureBackup: Boolean
): DolphinPatchResult =
    try {
        val original = target.readText()
        Log.i(TAG, "file: using ${target.path} size=${original.length}")
        if (ensureBackup) {
            ensureDolphinBackupFileExists(target, original)
            Log.d(TAG, "file: ensured backup ${dolphinBackupFileFor(target).path}")
        }
        val hardcoreWas = if (detectHardcore) detectDolphinHardcoreEnabled(original) else false
        val credentials = extractDolphinCredentials(original)
        val transformed = transform(original)
        Log.d(TAG, "file: transform changed=${transformed != original} hardcoreWas=$hardcoreWas")
        if (transformed == original) {
            DolphinPatchResult(success = true, message = context.getString(strings.noOpMessage), hardcoreWasEnabled = hardcoreWas, credentials = credentials)
        } else {
            writeDolphinFileAtomically(target, transformed)
            Log.i(TAG, "file: wrote updated config ${target.path}")
            DolphinPatchResult(success = true, message = context.getString(strings.successFile), hardcoreWasEnabled = hardcoreWas, credentials = credentials)
        }
    } catch (e: Exception) {
        Log.w(TAG, "file: error target=${target.path} message=${e.message}", e)
        DolphinPatchResult(success = false, message = context.getString(strings.errorFile, target.path, e.message))
    }

private fun ensureDolphinSafBackupExists(context: Context, directory: DocumentFile, originalContent: String): DocumentFile? {
    directory.findFile(DOLPHIN_CFG_BACKUP_NAME)?.let { return it }

    val backupFile = directory.createFile("application/octet-stream", DOLPHIN_CFG_BACKUP_NAME) ?: return null
    val output = context.contentResolver.openOutputStream(backupFile.uri, "wt") ?: return null
    output.use { it.write(originalContent.toByteArray()) }
    return backupFile
}

internal fun dolphinBackupFileFor(target: File): File = File(target.parentFile, DOLPHIN_CFG_BACKUP_NAME)

internal fun ensureDolphinBackupFileExists(target: File, originalContent: String) {
    val backup = dolphinBackupFileFor(target)
    if (backup.exists()) return
    backup.writeText(originalContent)
}

private fun writeDolphinFileAtomically(target: File, content: String) {
    val atomicFile = AtomicFile(target)
    val bytes = content.toByteArray()
    val output = atomicFile.startWrite()

    try {
        output.write(bytes)
        output.flush()
        atomicFile.finishWrite(output)
    } catch (e: Exception) {
        atomicFile.failWrite(output)
        throw e
    }
}

internal fun detectDolphinHardcoreEnabled(content: String): Boolean =
    extractDolphinAchievementValue(content, "HardcoreEnabled")
        ?.equals("true", ignoreCase = true)
        ?: false

internal fun extractDolphinCredentials(content: String): ImportedCredentials? {
    val username = extractDolphinAchievementValue(content, "Username")?.takeIf { it.isNotBlank() } ?: return null
    val token = extractDolphinAchievementValue(content, "ApiToken")?.takeIf { it.isNotBlank() } ?: return null
    return ImportedCredentials.Token(username = username, token = token)
}

internal fun buildDolphinCredentialsRestoredContent(
    content: String,
    proxyAddress: String,
    storedCredentials: LoginCredentials? = null
): String {
    val restoredFields = dolphinCredentialRestoreFields(content, proxyAddress, storedCredentials)
    if (restoredFields.isEmpty()) return content

    return updateDolphinAchievementsSection(content) {
        restoredFields.forEach { (key, value) -> put(key, value) }
    }
}

internal fun buildPatchedDolphinContent(
    content: String,
    proxyAddress: String,
    storedCredentials: LoginCredentials? = null
): String =
    updateDolphinAchievementsSection(content) {
        put("HostUrl", proxyAddress)
        put("HardcoreEnabled", "False")
        dolphinCredentialRestoreFields(content, proxyAddress, storedCredentials)
            .forEach { (key, value) -> put(key, value) }
    }

internal fun buildRevertedDolphinContent(content: String, restoreHardcore: Boolean = false): String =
    updateDolphinAchievementsSection(content) {
        put("HostUrl", "")
        put("HardcoreEnabled", if (restoreHardcore) "True" else "False")
    }

internal fun buildPatchedDolphinGameSettingsContent(content: String): DolphinGameSettingsUpdate? {
    val entry = findDolphinIniEntry(content, DOLPHIN_GAME_SETTINGS_SECTION, DOLPHIN_GAME_SETTINGS_KEY)
        ?: return null
    if (!entry.value.equals("true", ignoreCase = true)) {
        return null
    }

    return DolphinGameSettingsUpdate(
        content = replaceDolphinIniEntry(content, entry, "false"),
        originalValue = entry.value
    )
}

internal fun buildRevertedDolphinGameSettingsContent(content: String, originalValue: String): String? {
    val entry = findDolphinIniEntry(content, DOLPHIN_GAME_SETTINGS_SECTION, DOLPHIN_GAME_SETTINGS_KEY)
        ?: return null
    if (!entry.value.equals("false", ignoreCase = true)) {
        return null
    }

    return replaceDolphinIniEntry(content, entry, originalValue)
}

internal fun isDolphinPatchedContent(content: String, proxyAddress: String): Boolean =
    extractDolphinAchievementValue(content, "HostUrl") == proxyAddress

fun checkIsDolphinPatched(context: Context, treeUri: Uri?): Boolean {
    val proxyAddress = proxyValue(context)
    Log.d(TAG, "checkIsDolphinPatched: treeUri=$treeUri proxy=$proxyAddress")

    if (treeUri != null) {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
        if (tree != null) {
            for (segments in DOLPHIN_SAF_CFG_PATHS) {
                val cfgFile = segments.fold(tree as DocumentFile?) { dir, seg -> dir?.findFile(seg) }
                if (cfgFile == null || !cfgFile.exists()) continue
                return try {
                    val content = context.contentResolver.openInputStream(cfgFile.uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: return false
                    isDolphinPatchedContent(content, proxyAddress)
                } catch (_: Exception) {
                    false
                }
            }
        }
    }

    val directCandidate = DOLPHIN_SOURCE_CANDIDATES.map(::File).firstOrNull { it.exists() && it.canRead() }
    if (directCandidate != null) {
        return try {
            isDolphinPatchedContent(directCandidate.readText(), proxyAddress)
        } catch (_: Exception) {
            false
        }
    }

    return false
}

fun restoreDolphinCredentials(
    context: Context,
    treeUri: Uri?,
    storedCredentials: LoginCredentials?
): Boolean {
    if (storedCredentials == null || !isDolphinInstalled(context)) return false

    val proxyAddress = proxyValue(context)

    if (treeUri != null) {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
        if (tree != null) {
            for (segments in DOLPHIN_SAF_CFG_PATHS) {
                val cfgFile = segments.fold(tree as DocumentFile?) { dir, seg -> dir?.findFile(seg) }
                if (cfgFile == null || !cfgFile.exists()) continue
                return try {
                    val original = context.contentResolver.openInputStream(cfgFile.uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: return false
                    val restored = buildDolphinCredentialsRestoredContent(original, proxyAddress, storedCredentials)
                    if (restored != original) {
                        context.contentResolver.openOutputStream(cfgFile.uri, "wt")
                            ?.use { it.write(restored.toByteArray()) }
                            ?: return false
                    }
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }
    }

    val directCandidate = DOLPHIN_SOURCE_CANDIDATES.map(::File).firstOrNull { it.exists() && it.canWrite() }
    if (directCandidate != null) {
        return try {
            val original = directCandidate.readText()
            val restored = buildDolphinCredentialsRestoredContent(original, proxyAddress, storedCredentials)
            if (restored != original) {
                writeDolphinFileAtomically(directCandidate, restored)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    return false
}

private fun dolphinCredentialRestoreFields(
    content: String,
    proxyAddress: String,
    storedCredentials: LoginCredentials?
): Map<String, String> {
    if (storedCredentials == null) return emptyMap()
    if (!isDolphinPatchedContent(content, proxyAddress)) return emptyMap()

    val username = extractDolphinAchievementValue(content, "Username")?.takeIf { it.isNotBlank() }
    if (username != null && username != storedCredentials.user) return emptyMap()

    return linkedMapOf(
        "Enabled" to "true",
        "Username" to storedCredentials.user,
        "ApiToken" to storedCredentials.token
    )
}

private fun updateDolphinAchievementsSection(content: String, update: MutableMap<String, String>.() -> Unit): String {
    val replacements = linkedMapOf<String, String>().apply(update)
    val lines = content.split('\n').toMutableList()
    val sectionIndex = lines.indexOfFirst { it.trim() == "[Achievements]" }

    if (sectionIndex == -1) {
        val suffix = replacements.entries.joinToString("\n") { (key, value) -> "$key = $value" }
        return content.trimEnd() + "\n[Achievements]\n$suffix\n"
    }

    val sectionEnd = (sectionIndex + 1 until lines.size)
        .firstOrNull { lines[it].trim().startsWith('[') && lines[it].trim().endsWith(']') }
        ?: lines.size

    val remaining = replacements.toMutableMap()
    for (index in sectionIndex + 1 until sectionEnd) {
        val trimmed = lines[index].trim()
        val separator = trimmed.indexOf('=')
        if (separator == -1) continue
        val key = trimmed.substring(0, separator).trim()
        val value = remaining.remove(key) ?: continue
        val prefix = lines[index].substringBefore('=')
        lines[index] = "$prefix= $value"
    }

    if (remaining.isNotEmpty()) {
        val insertionIndex = sectionEnd
        val additions = remaining.entries.map { (key, value) -> "$key = $value" }
        lines.addAll(insertionIndex, additions)
    }

    return lines.joinToString("\n")
}

private fun extractDolphinAchievementValue(content: String, key: String): String? {
    val lines = content.lines()
    var inAchievements = false

    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.startsWith('[') && trimmed.endsWith(']')) {
            inAchievements = trimmed == "[Achievements]"
            continue
        }
        if (!inAchievements) continue

        val separator = trimmed.indexOf('=')
        if (separator == -1) continue
        val currentKey = trimmed.substring(0, separator).trim()
        if (currentKey != key) continue
        return trimmed.substring(separator + 1).trim()
    }

    return null
}

private fun findDolphinIniEntry(content: String, section: String, key: String): DolphinIniEntry? {
    val lines = content.split('\n')
    var inSection = false

    lines.forEachIndexed { index, line ->
        val trimmed = line.trim()
        if (trimmed.startsWith('[') && trimmed.endsWith(']')) {
            inSection = trimmed == "[$section]"
            return@forEachIndexed
        }
        if (!inSection) {
            return@forEachIndexed
        }

        val separator = trimmed.indexOf('=')
        if (separator == -1) {
            return@forEachIndexed
        }

        val currentKey = trimmed.substring(0, separator).trim()
        if (currentKey != key) {
            return@forEachIndexed
        }

        return DolphinIniEntry(
            lineIndex = index,
            prefix = line.substringBefore('='),
            value = trimmed.substring(separator + 1).trim()
        )
    }

    return null
}

private fun replaceDolphinIniEntry(content: String, entry: DolphinIniEntry, newValue: String): String {
    val lines = content.split('\n').toMutableList()
    lines[entry.lineIndex] = "${entry.prefix}= $newValue"
    return lines.joinToString("\n")
}
