package com.raofflineproxy.ui

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.AtomicFile
import androidx.documentfile.provider.DocumentFile
import com.raofflineproxy.proxyValue
import com.raofflineproxy.R
import java.io.File

private val EXT_STORAGE by lazy { Environment.getExternalStorageDirectory().path }
private const val CFG_BACKUP_NAME = "retroarch.raofflineproxy.cfg"

internal val RETROARCH_PACKAGE_CANDIDATES = listOf(
    "com.retroarch.aarch64",
    "com.retroarch"
)

private val SOURCE_CANDIDATES by lazy {
    RETROARCH_PACKAGE_CANDIDATES.flatMap { packageName ->
        listOf(
            "$EXT_STORAGE/Android/data/$packageName/files/retroarch.cfg",
            "/storage/emulated/0/Android/data/$packageName/files/retroarch.cfg"
        )
    } + listOf(
        "$EXT_STORAGE/RetroArch/retroarch.cfg",
        "/storage/emulated/0/RetroArch/retroarch.cfg"
    )
}

// Paths to try relative to whatever tree root the user granted.
// Covers: granted Android/data/ → full path needed
//         granted com.retroarch.aarch64 or com.retroarch → skip the package segment
//         granted files/ → skip package + files
private val SAF_CFG_PATHS = RETROARCH_PACKAGE_CANDIDATES.map { listOf(it, "files", "retroarch.cfg") } + listOf(
    listOf("files", "retroarch.cfg"),
    listOf("retroarch.cfg")
)

data class PatchResult(
    val success: Boolean,
    val message: String,
    val needsSafGrant: Boolean = false,
    val invalidSafGrant: Boolean = false,
    val copyBackPath: String? = null,
    val hardcoreWasEnabled: Boolean = false,
    val credentials: RetroArchCfgCredentials? = null
)

data class RetroArchCfgCredentials(
    val username: String,
    val token: String
)

private class CfgStrings(
    val noOpMessage: Int,
    val successSaf: Int,
    val errorSaf: Int,
    val successFile: Int,
    val errorFile: Int,
    val manualEditMode: ManualEditMode
)

private enum class ManualEditMode { Patch, Revert }

private val PATCH_STRINGS = CfgStrings(
    noOpMessage = R.string.patch_already_configured,
    successSaf = R.string.patch_success_saf,
    errorSaf = R.string.patch_error_saf,
    successFile = R.string.patch_success,
    errorFile = R.string.patch_error_file,
    manualEditMode = ManualEditMode.Patch
)

private val REVERT_STRINGS = CfgStrings(
    noOpMessage = R.string.revert_already_reverted,
    successSaf = R.string.revert_success_saf,
    errorSaf = R.string.revert_error_saf,
    successFile = R.string.revert_success,
    errorFile = R.string.revert_error_file,
    manualEditMode = ManualEditMode.Revert
)

internal fun patchManualEditInstructions(proxyAddress: String): String =
    "Could not patch retroarch.cfg automatically.\n\n" +
    "Grant Folder Access, or edit retroarch.cfg manually and set these exact lines:\n\n" +
    "cheevos_custom_host = \"$proxyAddress\"\n" +
    "cheevos_hardcore_mode_enable = \"false\""

internal fun revertManualEditInstructions(): String =
    "Could not revert retroarch.cfg automatically.\n\n" +
    "Grant Folder Access, or edit retroarch.cfg manually and set these exact lines:\n\n" +
    "cheevos_custom_host = \"\"\n" +
    "cheevos_hardcore_mode_enable = \"true\" if you want to restore hardcore mode."

fun patchRetroArchCfg(context: Context, treeUri: Uri?): PatchResult {
    val transform: (String) -> String = { buildPatchedContent(it, proxyValue(context)) }
    return applyCfgTransform(
        context,
        treeUri,
        transform,
        PATCH_STRINGS,
        detectHardcore = true,
        ensureBackup = true,
        extractCredentials = true
    )
}

fun revertRetroArchCfg(context: Context, treeUri: Uri?, restoreHardcore: Boolean = false): PatchResult {
    val transform: (String) -> String = { buildRevertedContent(it, restoreHardcore) }
    return applyCfgTransform(
        context,
        treeUri,
        transform,
        REVERT_STRINGS,
        detectHardcore = false,
        ensureBackup = false,
        extractCredentials = false
    )
}

private fun applyCfgTransform(
    context: Context,
    treeUri: Uri?,
    transform: (String) -> String,
    strings: CfgStrings,
    detectHardcore: Boolean,
    ensureBackup: Boolean,
    extractCredentials: Boolean
): PatchResult {
    if (treeUri != null) {
        val safResult = transformViaSaf(context, treeUri, transform, strings, detectHardcore, ensureBackup, extractCredentials)
        if (safResult != null) return safResult
    }

    val directCandidate = SOURCE_CANDIDATES.map(::File).firstOrNull { it.exists() }

    if (directCandidate != null && directCandidate.canWrite()) {
        return transformViaFile(context, directCandidate, transform, strings, detectHardcore, ensureBackup, extractCredentials)
    }

    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
        if (directCandidate != null || treeUri == null) {
            return PatchResult(
                success = false,
                message = context.getString(R.string.saf_dialog_message),
                needsSafGrant = true
            )
        }
    }

    return PatchResult(
        success = false,
        message = when (strings.manualEditMode) {
            ManualEditMode.Patch -> patchManualEditInstructions(proxyValue(context))
            ManualEditMode.Revert -> revertManualEditInstructions()
        }
    )
}

private fun transformViaSaf(
    context: Context,
    treeUri: Uri,
    transform: (String) -> String,
    strings: CfgStrings,
    detectHardcore: Boolean,
    ensureBackup: Boolean,
    extractCredentials: Boolean
): PatchResult? {
    val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return null

    for (segments in SAF_CFG_PATHS) {
        val cfgParent = segments.dropLast(1).fold(tree as DocumentFile?) { dir, seg -> dir?.findFile(seg) }
        val cfgFile = cfgParent?.findFile(segments.last())
        if (cfgFile == null || !cfgFile.exists()) continue

        return try {
            val original = context.contentResolver.openInputStream(cfgFile.uri)
                ?.bufferedReader()?.use { it.readText() }
                ?: return PatchResult(success = false, message = context.getString(R.string.patch_could_not_read, cfgFile.name))

            if (ensureBackup) {
                ensureSafBackupExists(context, cfgParent, original)
                    ?: return PatchResult(success = false, message = context.getString(strings.errorSaf, "Could not create $CFG_BACKUP_NAME"))
            }

            val hardcoreWas = if (detectHardcore) detectHardcoreEnabled(original) else false
            val credentials = if (extractCredentials) extractRetroArchCredentials(original) else null
            val transformed = transform(original)
            if (transformed == original) {
                PatchResult(
                    success = true,
                    message = context.getString(strings.noOpMessage),
                    hardcoreWasEnabled = hardcoreWas,
                    credentials = credentials
                )
            } else {
                context.contentResolver.openOutputStream(cfgFile.uri, "wt")
                    ?.use { it.write(transformed.toByteArray()) }
                    ?: return PatchResult(success = false, message = context.getString(R.string.patch_could_not_write, cfgFile.name))
                PatchResult(
                    success = true,
                    message = context.getString(strings.successSaf),
                    hardcoreWasEnabled = hardcoreWas,
                    credentials = credentials
                )
            }
        } catch (e: Exception) {
            PatchResult(success = false, message = context.getString(strings.errorSaf, e.message))
        }
    }

    return PatchResult(
        success = false,
        message = context.getString(R.string.patch_cfg_not_in_folder),
        invalidSafGrant = true
    )
}

private fun transformViaFile(
    context: Context,
    target: File,
    transform: (String) -> String,
    strings: CfgStrings,
    detectHardcore: Boolean,
    ensureBackup: Boolean,
    extractCredentials: Boolean
): PatchResult =
    try {
        val original = target.readText()
        if (ensureBackup) {
            ensureBackupFileExists(target, original)
        }
        val hardcoreWas = if (detectHardcore) detectHardcoreEnabled(original) else false
        val credentials = if (extractCredentials) extractRetroArchCredentials(original) else null
        val transformed = transform(original)
        if (transformed == original) {
            PatchResult(
                success = true,
                message = context.getString(strings.noOpMessage),
                hardcoreWasEnabled = hardcoreWas,
                credentials = credentials
            )
        } else {
            writeFileAtomically(target, transformed)
            PatchResult(
                success = true,
                message = context.getString(strings.successFile),
                hardcoreWasEnabled = hardcoreWas,
                credentials = credentials
            )
        }
    } catch (e: Exception) {
        PatchResult(success = false, message = context.getString(strings.errorFile, target.path, e.message))
    }

private fun ensureSafBackupExists(context: Context, directory: DocumentFile, originalContent: String): DocumentFile? {
    directory.findFile(CFG_BACKUP_NAME)?.let { return it }

    val backupFile = directory.createFile("application/octet-stream", CFG_BACKUP_NAME) ?: return null
    val output = context.contentResolver.openOutputStream(backupFile.uri, "wt") ?: return null

    output.use { it.write(originalContent.toByteArray()) }
    return backupFile
}

internal fun backupFileFor(target: File): File = File(target.parentFile, CFG_BACKUP_NAME)

internal fun ensureBackupFileExists(target: File, originalContent: String) {
    val backup = backupFileFor(target)
    if (backup.exists()) return

    backup.writeText(originalContent)
}

private fun writeFileAtomically(target: File, content: String) {
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

fun detectHardcoreEnabled(content: String): Boolean =
    Regex("""^\s*cheevos_hardcore_mode_enable\s*=\s*"true"\s*$""", RegexOption.MULTILINE)
        .containsMatchIn(content)

internal fun extractRetroArchCredentials(content: String): RetroArchCfgCredentials? {
    val username = extractRetroArchCfgValue(content, "cheevos_username")?.takeIf { it.isNotBlank() } ?: return null
    val token = extractRetroArchCfgValue(content, "cheevos_token")?.takeIf { it.isNotBlank() } ?: return null

    return RetroArchCfgCredentials(username = username, token = token)
}

private fun extractRetroArchCfgValue(content: String, key: String): String? =
    Regex("""^\s*${Regex.escape(key)}\s*=\s*"(.*)"\s*$""", RegexOption.MULTILINE)
        .find(content)
        ?.groupValues
        ?.get(1)
        ?.trim()

fun buildPatchedContent(content: String, proxyAddress: String): String {
    val hostRegex = Regex("""^(\s*cheevos_custom_host\s*=\s*).*$""", RegexOption.MULTILINE)
    val hardcoreRegex = Regex("""^(\s*cheevos_hardcore_mode_enable\s*=\s*).*$""", RegexOption.MULTILINE)

    val withHost = if (hostRegex.containsMatchIn(content)) {
        hostRegex.replace(content) { mr -> "${mr.groupValues[1]}\"$proxyAddress\"" }
    } else {
        content.trimEnd() + "\ncheevos_custom_host = \"$proxyAddress\"\n"
    }

    return if (hardcoreRegex.containsMatchIn(withHost)) {
        hardcoreRegex.replace(withHost) { mr -> "${mr.groupValues[1]}\"false\"" }
    } else {
        withHost.trimEnd() + "\ncheevos_hardcore_mode_enable = \"false\"\n"
    }
}

internal fun buildRevertedContent(content: String, restoreHardcore: Boolean = false): String {
    val hostRegex = Regex("""^(\s*cheevos_custom_host\s*=\s*).*$""", RegexOption.MULTILINE)
    val withHost = hostRegex.replace(content) { mr -> "${mr.groupValues[1]}\"\"" }

    if (!restoreHardcore) return withHost

    val hardcoreRegex = Regex("""^(\s*cheevos_hardcore_mode_enable\s*=\s*).*$""", RegexOption.MULTILINE)
    return if (hardcoreRegex.containsMatchIn(withHost)) {
        hardcoreRegex.replace(withHost) { mr -> "${mr.groupValues[1]}\"true\"" }
    } else {
        withHost
    }
}

internal fun isPatchedContent(content: String, proxyAddress: String): Boolean {
    val escaped = Regex.escape(proxyAddress)
    val hostRegex = Regex("""^\s*cheevos_custom_host\s*=\s*"$escaped"\s*$""", RegexOption.MULTILINE)
    return hostRegex.containsMatchIn(content)
}

fun checkIsPatched(context: Context, treeUri: Uri?): Boolean {
    val proxyAddress = proxyValue(context)

    if (treeUri != null) {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
        if (tree != null) {
            for (segments in SAF_CFG_PATHS) {
                val cfgFile = segments.fold(tree as DocumentFile?) { dir, seg -> dir?.findFile(seg) }
                if (cfgFile == null || !cfgFile.exists()) continue
                return try {
                    val content = context.contentResolver.openInputStream(cfgFile.uri)
                        ?.bufferedReader()?.use { it.readText() } ?: return false
                    isPatchedContent(content, proxyAddress)
                } catch (_: Exception) { false }
            }
        }
    }

    val directCandidate = SOURCE_CANDIDATES.map(::File).firstOrNull { it.exists() && it.canRead() }
    if (directCandidate != null) {
        return try { isPatchedContent(directCandidate.readText(), proxyAddress) } catch (_: Exception) { false }
    }

    return false
}
