package com.raofflineproxy.ui

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.AtomicFile
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.raofflineproxy.proxyValue
import com.raofflineproxy.R
import java.io.File

private val EXT_STORAGE by lazy { Environment.getExternalStorageDirectory().path }
private const val TAG = "RAProxy/RetroArchCfg"
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
    val credentials: ImportedCredentials? = null
)

private class CfgStrings(
    val noOpMessage: Int,
    val successSaf: Int,
    val errorSaf: Int,
    val successFile: Int,
    val errorFile: Int,
    val unavailableError: Int
)

private val PATCH_STRINGS = CfgStrings(
    noOpMessage = R.string.patch_already_configured,
    successSaf = R.string.patch_success_saf,
    errorSaf = R.string.patch_error_saf,
    successFile = R.string.patch_success,
    errorFile = R.string.patch_error_file,
    unavailableError = R.string.patch_error_unavailable
)

private val REVERT_STRINGS = CfgStrings(
    noOpMessage = R.string.revert_already_reverted,
    successSaf = R.string.revert_success_saf,
    errorSaf = R.string.revert_error_saf,
    successFile = R.string.revert_success,
    errorFile = R.string.revert_error_file,
    unavailableError = R.string.revert_error_unavailable
)

fun patchRetroArchCfg(context: Context, treeUri: Uri?): PatchResult {
    Log.i(TAG, "patch: starting treeUri=$treeUri proxy=${proxyValue(context)}")
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
    Log.i(TAG, "revert: starting treeUri=$treeUri restoreHardcore=$restoreHardcore")
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
    Log.d(TAG, "apply: treeUri=$treeUri detectHardcore=$detectHardcore ensureBackup=$ensureBackup candidates=${SOURCE_CANDIDATES.size}")
    if (treeUri != null) {
        val safResult = transformViaSaf(context, treeUri, transform, strings, detectHardcore, ensureBackup, extractCredentials)
        if (safResult != null) {
            Log.i(TAG, "apply: SAF result success=${safResult.success} needsSafGrant=${safResult.needsSafGrant} invalidSafGrant=${safResult.invalidSafGrant} copyBackPath=${safResult.copyBackPath}")
            return safResult
        }
        Log.d(TAG, "apply: treeUri present but SAF tree could not be opened")
    }

    val directCandidate = SOURCE_CANDIDATES.map(::File).firstOrNull { it.exists() }
    Log.d(TAG, "apply: directCandidate=${directCandidate?.path} writable=${directCandidate?.canWrite()}")

    if (directCandidate != null && directCandidate.canWrite()) {
        return transformViaFile(context, directCandidate, transform, strings, detectHardcore, ensureBackup, extractCredentials)
    }

    if (directCandidate != null || treeUri == null) {
        Log.w(TAG, "apply: requesting SAF grant directCandidate=${directCandidate?.path} treeUri=$treeUri sdk=${Build.VERSION.SDK_INT}")
        return PatchResult(
            success = false,
            message = context.getString(R.string.saf_dialog_message),
            needsSafGrant = true
        )
    }

    Log.w(TAG, "apply: automatic patching unavailable")
    return PatchResult(
        success = false,
        message = context.getString(strings.unavailableError)
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
    Log.d(TAG, "saf: opened tree uri=$treeUri name=${tree.name}")

    for (segments in SAF_CFG_PATHS) {
        Log.d(TAG, "saf: trying segments=${segments.joinToString("/")}")
        val cfgParent = segments.dropLast(1).fold(tree as DocumentFile?) { dir, seg -> dir?.findFile(seg) }
        val cfgFile = cfgParent?.findFile(segments.last())
        if (cfgFile == null || !cfgFile.exists()) {
            Log.d(TAG, "saf: not found segments=${segments.joinToString("/")}")
            continue
        }

        return try {
            val original = context.contentResolver.openInputStream(cfgFile.uri)
                ?.bufferedReader()?.use { it.readText() }
                ?: return PatchResult(success = false, message = context.getString(R.string.patch_could_not_read, cfgFile.name))
            Log.i(TAG, "saf: found cfg uri=${cfgFile.uri} size=${original.length}")

            if (ensureBackup) {
                ensureSafBackupExists(context, cfgParent, original)
                    ?: return PatchResult(success = false, message = context.getString(strings.errorSaf, "Could not create $CFG_BACKUP_NAME"))
                Log.d(TAG, "saf: ensured backup $CFG_BACKUP_NAME")
            }

            val hardcoreWas = if (detectHardcore) detectHardcoreEnabled(original) else false
            val credentials = if (extractCredentials) extractRetroArchCredentials(original) else null
            val transformed = transform(original)
            Log.d(TAG, "saf: transform changed=${transformed != original} hardcoreWas=$hardcoreWas hasCredentials=${credentials != null}")
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
                Log.i(TAG, "saf: wrote updated config uri=${cfgFile.uri}")
                PatchResult(
                    success = true,
                    message = context.getString(strings.successSaf),
                    hardcoreWasEnabled = hardcoreWas,
                    credentials = credentials
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "saf: error ${e.message}", e)
            PatchResult(success = false, message = context.getString(strings.errorSaf, e.message))
        }
    }

    Log.w(TAG, "saf: config not found in granted tree")
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
        Log.i(TAG, "file: using ${target.path} size=${original.length}")
        if (ensureBackup) {
            ensureBackupFileExists(target, original)
            Log.d(TAG, "file: ensured backup ${backupFileFor(target).path}")
        }
        val hardcoreWas = if (detectHardcore) detectHardcoreEnabled(original) else false
        val credentials = if (extractCredentials) extractRetroArchCredentials(original) else null
        val transformed = transform(original)
        Log.d(TAG, "file: transform changed=${transformed != original} hardcoreWas=$hardcoreWas hasCredentials=${credentials != null}")
        if (transformed == original) {
            PatchResult(
                success = true,
                message = context.getString(strings.noOpMessage),
                hardcoreWasEnabled = hardcoreWas,
                credentials = credentials
            )
        } else {
            writeFileAtomically(target, transformed)
            Log.i(TAG, "file: wrote updated config ${target.path}")
            PatchResult(
                success = true,
                message = context.getString(strings.successFile),
                hardcoreWasEnabled = hardcoreWas,
                credentials = credentials
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "file: error target=${target.path} message=${e.message}", e)
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

internal fun extractRetroArchCredentials(content: String): ImportedCredentials? {
    val username = extractRetroArchCfgValue(content, "cheevos_username")?.takeIf { it.isNotBlank() } ?: return null
    val token = extractRetroArchCfgValue(content, "cheevos_token")?.takeIf { it.isNotBlank() }
    if (token != null) {
        return ImportedCredentials.Token(username = username, token = token)
    }

    val password = extractRetroArchCfgValue(content, "cheevos_password")?.takeIf { it.isNotBlank() } ?: return null
    return ImportedCredentials.Password(username = username, password = password)
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
    Log.d(TAG, "checkIsPatched: treeUri=$treeUri proxy=$proxyAddress")

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
