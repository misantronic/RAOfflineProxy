package com.raofflineproxy.ui

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import com.raofflineproxy.PROXY_VALUE
import com.raofflineproxy.R
import java.io.File

private const val WORK_DIR = "/sdcard/RAOfflineProxy"
private const val WORK_CFG = "$WORK_DIR/retroarch.cfg"

private val SOURCE_CANDIDATES = listOf(
    "/sdcard/Android/data/com.retroarch.aarch64/files/retroarch.cfg",
    "/storage/emulated/0/Android/data/com.retroarch.aarch64/files/retroarch.cfg",
    "/sdcard/Android/data/com.retroarch/files/retroarch.cfg",
    "/storage/emulated/0/Android/data/com.retroarch/files/retroarch.cfg",
    "/sdcard/RetroArch/retroarch.cfg",
    "/storage/emulated/0/RetroArch/retroarch.cfg"
)

// Paths to try relative to whatever tree root the user granted.
// Covers: granted Android/data/ → full path needed
//         granted com.retroarch.aarch64 or com.retroarch → skip the package segment
//         granted files/ → skip package + files
private val SAF_CFG_PATHS = listOf(
    listOf("com.retroarch.aarch64", "files", "retroarch.cfg"),
    listOf("com.retroarch", "files", "retroarch.cfg"),
    listOf("files", "retroarch.cfg"),
    listOf("retroarch.cfg")
)

data class PatchResult(
    val success: Boolean,
    val message: String,
    val needsSafGrant: Boolean = false,
    val copyBackPath: String? = null,
    val hardcoreWasEnabled: Boolean = false
)

private class CfgStrings(
    val noOpMessage: Int,
    val successSaf: Int,
    val errorSaf: Int,
    val successFile: Int,
    val errorFile: Int,
    val noWriteGrantFolder: Int,
    val notFoundGrantFolder: Int,
    val successDirect: Int,
    val stagedNoCopyBack: Int,
    val stagedWithCopyBack: Int
)

private val PATCH_STRINGS = CfgStrings(
    noOpMessage = R.string.patch_already_configured,
    successSaf = R.string.patch_success_saf,
    errorSaf = R.string.patch_error_saf,
    successFile = R.string.patch_success,
    errorFile = R.string.patch_error_file,
    noWriteGrantFolder = R.string.patch_cfg_no_write_grant_folder,
    notFoundGrantFolder = R.string.patch_cfg_not_found_grant_folder,
    successDirect = R.string.patch_success,
    stagedNoCopyBack = R.string.patch_staged_no_copy_back,
    stagedWithCopyBack = R.string.patch_staged_with_copy_back
)

private val REVERT_STRINGS = CfgStrings(
    noOpMessage = R.string.revert_already_reverted,
    successSaf = R.string.revert_success_saf,
    errorSaf = R.string.revert_error_saf,
    successFile = R.string.revert_success,
    errorFile = R.string.revert_error_file,
    noWriteGrantFolder = R.string.revert_cfg_no_write_grant_folder,
    notFoundGrantFolder = R.string.revert_cfg_not_found_grant_folder,
    successDirect = R.string.revert_success,
    stagedNoCopyBack = R.string.revert_staged_no_copy_back,
    stagedWithCopyBack = R.string.revert_staged_with_copy_back
)

fun patchRetroArchCfg(context: Context, treeUri: Uri?): PatchResult {
    val transform: (String) -> String = { buildPatchedContent(it) }
    return applyCfgTransform(context, treeUri, transform, PATCH_STRINGS, detectHardcore = true)
}

fun revertRetroArchCfg(context: Context, treeUri: Uri?, restoreHardcore: Boolean = false): PatchResult {
    val transform: (String) -> String = { buildRevertedContent(it, restoreHardcore) }
    return applyCfgTransform(context, treeUri, transform, REVERT_STRINGS, detectHardcore = false)
}

private fun applyCfgTransform(
    context: Context,
    treeUri: Uri?,
    transform: (String) -> String,
    strings: CfgStrings,
    detectHardcore: Boolean
): PatchResult {
    if (treeUri != null) {
        val safResult = transformViaSaf(context, treeUri, transform, strings, detectHardcore)
        if (safResult != null) return safResult
    }

    val directCandidate = SOURCE_CANDIDATES.map(::File).firstOrNull { it.exists() }

    if (directCandidate != null && directCandidate.canWrite()) {
        return transformViaFile(context, directCandidate, transform, strings, detectHardcore)
    }

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M && detectHardcore) {
        return PatchResult(success = false, message = context.getString(R.string.patch_cfg_not_found))
    }

    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
        if (directCandidate != null || treeUri == null) {
            return PatchResult(
                success = false,
                message = if (directCandidate != null)
                    context.getString(strings.noWriteGrantFolder)
                else
                    context.getString(strings.notFoundGrantFolder),
                needsSafGrant = true
            )
        }
    }

    return stagingTransform(context, directCandidate, transform, strings, detectHardcore)
}

private fun transformViaSaf(
    context: Context,
    treeUri: Uri,
    transform: (String) -> String,
    strings: CfgStrings,
    detectHardcore: Boolean
): PatchResult? {
    val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return null

    for (segments in SAF_CFG_PATHS) {
        val cfgFile = segments.fold(tree as DocumentFile?) { dir, seg -> dir?.findFile(seg) }
        if (cfgFile == null || !cfgFile.exists()) continue

        return try {
            val original = context.contentResolver.openInputStream(cfgFile.uri)
                ?.bufferedReader()?.use { it.readText() }
                ?: return PatchResult(success = false, message = context.getString(R.string.patch_could_not_read, cfgFile.name))

            val hardcoreWas = if (detectHardcore) detectHardcoreEnabled(original) else false
            val transformed = transform(original)
            if (transformed == original) {
                PatchResult(success = true, message = context.getString(strings.noOpMessage), hardcoreWasEnabled = hardcoreWas)
            } else {
                context.contentResolver.openOutputStream(cfgFile.uri, "wt")
                    ?.use { it.write(transformed.toByteArray()) }
                    ?: return PatchResult(success = false, message = context.getString(R.string.patch_could_not_write, cfgFile.name))
                PatchResult(success = true, message = context.getString(strings.successSaf), hardcoreWasEnabled = hardcoreWas)
            }
        } catch (e: Exception) {
            PatchResult(success = false, message = context.getString(strings.errorSaf, e.message))
        }
    }

    return PatchResult(
        success = false,
        message = context.getString(R.string.patch_cfg_not_in_folder)
    )
}

private fun transformViaFile(
    context: Context,
    target: File,
    transform: (String) -> String,
    strings: CfgStrings,
    detectHardcore: Boolean
): PatchResult =
    try {
        val original = target.readText()
        val hardcoreWas = if (detectHardcore) detectHardcoreEnabled(original) else false
        val transformed = transform(original)
        if (transformed == original) {
            PatchResult(success = true, message = context.getString(strings.noOpMessage), hardcoreWasEnabled = hardcoreWas)
        } else {
            target.writeText(transformed)
            PatchResult(success = true, message = context.getString(strings.successFile), hardcoreWasEnabled = hardcoreWas)
        }
    } catch (e: Exception) {
        PatchResult(success = false, message = context.getString(strings.errorFile, target.path, e.message))
    }

private fun stagingTransform(
    context: Context,
    directCandidate: File?,
    transform: (String) -> String,
    strings: CfgStrings,
    detectHardcore: Boolean
): PatchResult {
    File(WORK_DIR).mkdirs()
    val workCfg = File(WORK_CFG)

    if (directCandidate != null && !workCfg.exists()) {
        try {
            directCandidate.copyTo(workCfg, overwrite = true)
        } catch (_: Exception) {
            return PatchResult(
                success = false,
                message = manualCopyInstructions(context, directCandidate.path)
            )
        }
    }

    if (!workCfg.exists()) {
        return PatchResult(
            success = false,
            message = manualCopyInstructions(context, SOURCE_CANDIDATES.first())
        )
    }

    val fileResult = transformViaFile(context, workCfg, transform, strings, detectHardcore)
    if (!fileResult.success) return fileResult

    if (directCandidate != null) {
        return try {
            workCfg.copyTo(directCandidate, overwrite = true)
            PatchResult(success = true, message = context.getString(strings.successDirect), hardcoreWasEnabled = fileResult.hardcoreWasEnabled)
        } catch (_: Exception) {
            PatchResult(
                success = true,
                message = context.getString(strings.stagedNoCopyBack, WORK_CFG),
                copyBackPath = directCandidate.path,
                hardcoreWasEnabled = fileResult.hardcoreWasEnabled
            )
        }
    }

    return PatchResult(
        success = true,
        message = context.getString(strings.stagedWithCopyBack, WORK_CFG),
        copyBackPath = SOURCE_CANDIDATES.first(),
        hardcoreWasEnabled = fileResult.hardcoreWasEnabled
    )
}

private fun manualCopyInstructions(context: Context, sourcePath: String): String =
    context.getString(R.string.patch_manual_copy_instructions, sourcePath, WORK_CFG)

fun detectHardcoreEnabled(content: String): Boolean =
    Regex("""^\s*cheevos_hardcore_mode_enable\s*=\s*"true"\s*$""", RegexOption.MULTILINE)
        .containsMatchIn(content)

fun buildPatchedContent(content: String): String {
    val hostRegex = Regex("""^(\s*cheevos_custom_host\s*=\s*).*$""", RegexOption.MULTILINE)
    val hardcoreRegex = Regex("""^(\s*cheevos_hardcore_mode_enable\s*=\s*).*$""", RegexOption.MULTILINE)

    val withHost = if (hostRegex.containsMatchIn(content)) {
        hostRegex.replace(content) { mr -> "${mr.groupValues[1]}\"$PROXY_VALUE\"" }
    } else {
        content.trimEnd() + "\ncheevos_custom_host = \"$PROXY_VALUE\"\n"
    }

    return if (hardcoreRegex.containsMatchIn(withHost)) {
        hardcoreRegex.replace(withHost) { mr -> "${mr.groupValues[1]}\"false\"" }
    } else {
        withHost.trimEnd() + "\ncheevos_hardcore_mode_enable = \"false\"\n"
    }
}

private fun buildRevertedContent(content: String, restoreHardcore: Boolean = false): String {
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

private fun isPatchedContent(content: String): Boolean {
    val escaped = Regex.escape(PROXY_VALUE)
    val hostRegex = Regex("""^\s*cheevos_custom_host\s*=\s*"$escaped"\s*$""", RegexOption.MULTILINE)
    return hostRegex.containsMatchIn(content)
}

fun checkIsPatched(context: Context, treeUri: Uri?): Boolean {
    if (treeUri != null) {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
        if (tree != null) {
            for (segments in SAF_CFG_PATHS) {
                val cfgFile = segments.fold(tree as DocumentFile?) { dir, seg -> dir?.findFile(seg) }
                if (cfgFile == null || !cfgFile.exists()) continue
                return try {
                    val content = context.contentResolver.openInputStream(cfgFile.uri)
                        ?.bufferedReader()?.use { it.readText() } ?: return false
                    isPatchedContent(content)
                } catch (_: Exception) { false }
            }
        }
    }

    val directCandidate = SOURCE_CANDIDATES.map(::File).firstOrNull { it.exists() && it.canRead() }
    if (directCandidate != null) {
        return try { isPatchedContent(directCandidate.readText()) } catch (_: Exception) { false }
    }

    return false
}
