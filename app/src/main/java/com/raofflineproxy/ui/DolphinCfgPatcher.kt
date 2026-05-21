package com.raofflineproxy.ui

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.AtomicFile
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.raofflineproxy.R
import com.raofflineproxy.proxyValue
import java.io.File

private val DOLPHIN_EXT_STORAGE by lazy { Environment.getExternalStorageDirectory().path }
private const val TAG = "RAProxy/DolphinCfg"
private const val DOLPHIN_CFG_BACKUP_NAME = "RetroAchievements.raofflineproxy.ini"
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

fun patchDolphinCfg(context: Context, treeUri: Uri?): DolphinPatchResult {
    if (!isDolphinInstalled(context)) {
        Log.i(TAG, "patch: Dolphin not installed")
        return DolphinPatchResult(success = true, message = "Dolphin not installed.", skippedNotInstalled = true)
    }

    Log.i(TAG, "patch: starting treeUri=$treeUri proxy=${proxyValue(context)}")

    val transform: (String) -> String = { buildPatchedDolphinContent(it, proxyValue(context)) }
    return applyDolphinTransform(
        context = context,
        treeUri = treeUri,
        transform = transform,
        strings = DOLPHIN_PATCH_STRINGS,
        detectHardcore = true,
        ensureBackup = true
    )
}

fun revertDolphinCfg(context: Context, treeUri: Uri?, restoreHardcore: Boolean = false): DolphinPatchResult {
    if (!isDolphinInstalled(context)) {
        Log.i(TAG, "revert: Dolphin not installed")
        return DolphinPatchResult(success = true, message = "Dolphin not installed.", skippedNotInstalled = true)
    }

    Log.i(TAG, "revert: starting treeUri=$treeUri restoreHardcore=$restoreHardcore")

    val transform: (String) -> String = { buildRevertedDolphinContent(it, restoreHardcore) }
    return applyDolphinTransform(
        context = context,
        treeUri = treeUri,
        transform = transform,
        strings = DOLPHIN_REVERT_STRINGS,
        detectHardcore = false,
        ensureBackup = false
    )
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

internal fun buildPatchedDolphinContent(content: String, proxyAddress: String): String =
    updateDolphinAchievementsSection(content) {
        put("HostUrl", proxyAddress)
        put("HardcoreEnabled", "False")
    }

internal fun buildRevertedDolphinContent(content: String, restoreHardcore: Boolean = false): String =
    updateDolphinAchievementsSection(content) {
        put("HostUrl", "")
        put("HardcoreEnabled", if (restoreHardcore) "True" else "False")
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
