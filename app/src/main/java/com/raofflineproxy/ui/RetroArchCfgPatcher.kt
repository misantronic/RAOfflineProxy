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

fun patchRetroArchCfg(context: Context, treeUri: Uri?): PatchResult {
    if (treeUri != null) {
        val safResult = patchViaSaf(context, treeUri)
        if (safResult != null) return safResult
    }

    val directCandidate = SOURCE_CANDIDATES.map(::File).firstOrNull { it.exists() }

    if (directCandidate != null && directCandidate.canWrite()) {
        return writeViaFile(context, directCandidate)
    }

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
        return PatchResult(success = false, message = context.getString(R.string.patch_cfg_not_found))
    }

    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
        if (directCandidate != null || treeUri == null) {
            return PatchResult(
                success = false,
                message = if (directCandidate != null)
                    context.getString(R.string.patch_cfg_no_write_grant_folder)
                else
                    context.getString(R.string.patch_cfg_not_found_grant_folder),
                needsSafGrant = true
            )
        }
    }

    return stagingPatch(context, directCandidate)
}

private fun patchViaSaf(context: Context, treeUri: Uri): PatchResult? {
    val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return null

    for (segments in SAF_CFG_PATHS) {
        val cfgFile = segments.fold(tree as DocumentFile?) { dir, seg -> dir?.findFile(seg) }
        if (cfgFile == null || !cfgFile.exists()) continue

        return try {
            val original = context.contentResolver.openInputStream(cfgFile.uri)
                ?.bufferedReader()?.use { it.readText() }
                ?: return PatchResult(success = false, message = context.getString(R.string.patch_could_not_read, cfgFile.name))

            val hardcoreWas = detectHardcoreEnabled(original)
            val patched = buildPatchedContent(original)
            if (patched == original) {
                PatchResult(success = true, message = context.getString(R.string.patch_already_configured), hardcoreWasEnabled = hardcoreWas)
            } else {
                context.contentResolver.openOutputStream(cfgFile.uri, "wt")
                    ?.use { it.write(patched.toByteArray()) }
                    ?: return PatchResult(success = false, message = context.getString(R.string.patch_could_not_write, cfgFile.name))
                PatchResult(success = true, message = context.getString(R.string.patch_success_saf), hardcoreWasEnabled = hardcoreWas)
            }
        } catch (e: Exception) {
            PatchResult(success = false, message = context.getString(R.string.patch_error_saf, e.message))
        }
    }

    return PatchResult(
        success = false,
        message = context.getString(R.string.patch_cfg_not_in_folder)
    )
}

private fun stagingPatch(context: Context, directCandidate: File?): PatchResult {
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

    val patchResult = writeViaFile(context, workCfg)
    if (!patchResult.success) return patchResult

    if (directCandidate != null) {
        return try {
            workCfg.copyTo(directCandidate, overwrite = true)
            PatchResult(success = true, message = context.getString(R.string.patch_success), hardcoreWasEnabled = patchResult.hardcoreWasEnabled)
        } catch (_: Exception) {
            PatchResult(
                success = true,
                message = context.getString(R.string.patch_staged_no_copy_back, WORK_CFG),
                copyBackPath = directCandidate.path,
                hardcoreWasEnabled = patchResult.hardcoreWasEnabled
            )
        }
    }

    return PatchResult(
        success = true,
        message = context.getString(R.string.patch_staged_with_copy_back, WORK_CFG),
        copyBackPath = SOURCE_CANDIDATES.first(),
        hardcoreWasEnabled = patchResult.hardcoreWasEnabled
    )
}

private fun writeViaFile(context: Context, target: File): PatchResult =
    try {
        val original = target.readText()
        val hardcoreWas = detectHardcoreEnabled(original)
        val patched = buildPatchedContent(original)
        if (patched == original) {
            PatchResult(success = true, message = context.getString(R.string.patch_already_configured), hardcoreWasEnabled = hardcoreWas)
        } else {
            target.writeText(patched)
            PatchResult(success = true, message = context.getString(R.string.patch_success), hardcoreWasEnabled = hardcoreWas)
        }
    } catch (e: Exception) {
        PatchResult(success = false, message = context.getString(R.string.patch_error_file, target.path, e.message))
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

fun revertRetroArchCfg(context: Context, treeUri: Uri?, restoreHardcore: Boolean = false): PatchResult {
    if (treeUri != null) {
        val safResult = revertViaSaf(context, treeUri, restoreHardcore)
        if (safResult != null) return safResult
    }

    val directCandidate = SOURCE_CANDIDATES.map(::File).firstOrNull { it.exists() }

    if (directCandidate != null && directCandidate.canWrite()) {
        return revertViaFile(context, directCandidate, restoreHardcore)
    }

    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
        if (directCandidate != null || treeUri == null) {
            return PatchResult(
                success = false,
                message = if (directCandidate != null)
                    context.getString(R.string.revert_cfg_no_write_grant_folder)
                else
                    context.getString(R.string.revert_cfg_not_found_grant_folder),
                needsSafGrant = true
            )
        }
    }

    return stagingRevert(context, directCandidate, restoreHardcore)
}

private fun revertViaSaf(context: Context, treeUri: Uri, restoreHardcore: Boolean): PatchResult? {
    val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return null

    for (segments in SAF_CFG_PATHS) {
        val cfgFile = segments.fold(tree as DocumentFile?) { dir, seg -> dir?.findFile(seg) }
        if (cfgFile == null || !cfgFile.exists()) continue

        return try {
            val original = context.contentResolver.openInputStream(cfgFile.uri)
                ?.bufferedReader()?.use { it.readText() }
                ?: return PatchResult(success = false, message = context.getString(R.string.patch_could_not_read, cfgFile.name))

            val reverted = buildRevertedContent(original, restoreHardcore)
            if (reverted == original) {
                PatchResult(success = true, message = context.getString(R.string.revert_already_reverted))
            } else {
                context.contentResolver.openOutputStream(cfgFile.uri, "wt")
                    ?.use { it.write(reverted.toByteArray()) }
                    ?: return PatchResult(success = false, message = context.getString(R.string.patch_could_not_write, cfgFile.name))
                PatchResult(success = true, message = context.getString(R.string.revert_success_saf))
            }
        } catch (e: Exception) {
            PatchResult(success = false, message = context.getString(R.string.revert_error_saf, e.message))
        }
    }

    return PatchResult(
        success = false,
        message = context.getString(R.string.patch_cfg_not_in_folder)
    )
}

private fun revertViaFile(context: Context, target: File, restoreHardcore: Boolean): PatchResult =
    try {
        val original = target.readText()
        val reverted = buildRevertedContent(original, restoreHardcore)
        if (reverted == original) {
            PatchResult(success = true, message = context.getString(R.string.revert_already_reverted))
        } else {
            target.writeText(reverted)
            PatchResult(success = true, message = context.getString(R.string.revert_success))
        }
    } catch (e: Exception) {
        PatchResult(success = false, message = context.getString(R.string.revert_error_file, target.path, e.message))
    }

private fun stagingRevert(context: Context, directCandidate: File?, restoreHardcore: Boolean): PatchResult {
    File(WORK_DIR).mkdirs()
    val workCfg = File(WORK_CFG)

    if (directCandidate != null && !workCfg.exists()) {
        try {
            directCandidate.copyTo(workCfg, overwrite = true)
        } catch (_: Exception) {
            return PatchResult(success = false, message = manualCopyInstructions(context, directCandidate.path))
        }
    }

    if (!workCfg.exists()) {
        return PatchResult(success = false, message = manualCopyInstructions(context, SOURCE_CANDIDATES.first()))
    }

    val revertResult = revertViaFile(context, workCfg, restoreHardcore)
    if (!revertResult.success) return revertResult

    if (directCandidate != null) {
        return try {
            workCfg.copyTo(directCandidate, overwrite = true)
            PatchResult(success = true, message = context.getString(R.string.revert_success))
        } catch (_: Exception) {
            PatchResult(
                success = true,
                message = context.getString(R.string.revert_staged_no_copy_back, WORK_CFG),
                copyBackPath = directCandidate.path
            )
        }
    }

    return PatchResult(
        success = true,
        message = context.getString(R.string.revert_staged_with_copy_back, WORK_CFG),
        copyBackPath = SOURCE_CANDIDATES.first()
    )
}
