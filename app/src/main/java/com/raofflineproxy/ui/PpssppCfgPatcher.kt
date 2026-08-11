package com.raofflineproxy.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import com.raofflineproxy.PrefsConstants
import com.raofflineproxy.R
import com.raofflineproxy.proxyBase
import com.raofflineproxy.proxyPort
import com.raofflineproxy.proxyValue

internal const val PPSSPP_PSP_DIR = "PSP"
internal const val PPSSPP_SYSTEM_DIR = "SYSTEM"
internal const val PPSSPP_INI_FILE = "ppsspp.ini"
internal const val PPSSPP_SET_HOST_OVERRIDE_ACTION = "org.ppsspp.ppsspp.action.SET_ACHIEVEMENTS_HOST_OVERRIDE"
internal const val PPSSPP_CLEAR_HOST_OVERRIDE_ACTION = "org.ppsspp.ppsspp.action.CLEAR_ACHIEVEMENTS_HOST_OVERRIDE"
internal const val PPSSPP_HOST_OVERRIDE_EXTRA = "host"

private val PPSSPP_SAF_ROOT_PATHS = listOf(
    *UI_PPSSPP_PACKAGE_CANDIDATES.map { listOf(it, "files") }.toTypedArray(),
    *UI_PPSSPP_PACKAGE_CANDIDATES.map { listOf(it, "files", PPSSPP_PSP_DIR) }.toTypedArray(),
    listOf("files"),
    listOf("files", PPSSPP_PSP_DIR),
    listOf(PPSSPP_PSP_DIR),
    emptyList()
)

private data class PpssppStrings(
    val noOpMessage: Int,
    val successSaf: Int,
    val errorSaf: Int,
    val successFile: Int,
    val errorFile: Int,
    val configMissingInFolder: Int,
    val unavailableError: Int
)

private val PPSSPP_PATCH_STRINGS = PpssppStrings(
    noOpMessage = R.string.ppsspp_patch_already_configured,
    successSaf = R.string.ppsspp_patch_success_saf,
    errorSaf = R.string.ppsspp_patch_error_saf,
    successFile = R.string.ppsspp_patch_success,
    errorFile = R.string.ppsspp_patch_error_file,
    configMissingInFolder = R.string.ppsspp_patch_cfg_not_in_folder,
    unavailableError = R.string.ppsspp_patch_error_unavailable
)

private val PPSSPP_REVERT_STRINGS = PpssppStrings(
    noOpMessage = R.string.ppsspp_revert_already_reverted,
    successSaf = R.string.ppsspp_revert_success_saf,
    errorSaf = R.string.ppsspp_revert_error_saf,
    successFile = R.string.ppsspp_revert_success,
    errorFile = R.string.ppsspp_revert_error_file,
    configMissingInFolder = R.string.ppsspp_patch_cfg_not_in_folder,
    unavailableError = R.string.ppsspp_revert_error_unavailable
)

internal fun isPpssppInstalled(context: Context): Boolean =
    resolveInstalledPackage(context, UI_PPSSPP_PACKAGE_CANDIDATES) != null

fun patchPpssppCfg(context: Context, treeUri: Uri?): ConfigPatchResult {
    if (!isPpssppInstalled(context)) {
        return ConfigPatchResult(success = true, message = "PPSSPP not installed.", skippedNotInstalled = true)
    }

    broadcastPpssppHostOverride(context)?.let { return it }

    return applyPpssppTransform(
        context = context,
        treeUri = treeUri,
        transform = { buildPatchedPpssppContent(it, proxyValue(context)) },
        strings = PPSSPP_PATCH_STRINGS,
        extractCredentials = true,
        detectHardcore = true
    )
}

fun revertPpssppCfg(context: Context, treeUri: Uri?, restoreHardcore: Boolean = false): ConfigPatchResult {
    if (!isPpssppInstalled(context)) {
        return ConfigPatchResult(success = true, message = "PPSSPP not installed.", skippedNotInstalled = true)
    }

    clearPpssppHostOverride(context)?.let { return it }

    return applyPpssppTransform(
        context = context,
        treeUri = treeUri,
        transform = { buildRevertedPpssppContent(it, restoreHardcore) },
        strings = PPSSPP_REVERT_STRINGS,
        extractCredentials = false,
        detectHardcore = false
    )
}

fun checkIsPpssppPatched(context: Context, treeUri: Uri?): Boolean {
    if (supportsPpssppBroadcastOverride(context)) {
        val prefs = context.getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(PrefsConstants.KEY_PPSSPP_PATCHED_THIS_RUN, false)
    }

    val proxyAddress = proxyValue(context)
    val tree = treeUri?.let { DocumentFile.fromTreeUri(context, it) } ?: return false
    val iniFile = resolvePpssppIni(tree) ?: return false
    val content = runCatching {
        context.contentResolver.openInputStream(iniFile.uri)
            ?.bufferedReader()
            ?.use { it.readText() }
    }.getOrNull() ?: return false
    return isPpssppPatchedContent(content, proxyAddress)
}

internal fun validatePpssppRoot(context: Context, treeUri: Uri): Boolean {
    if (supportsPpssppBroadcastOverride(context)) {
        return true
    }

    val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return false
    return resolvePpssppIni(tree) != null
}

internal fun supportsPpssppBroadcastOverride(context: Context): Boolean {
    val packageName = resolveInstalledPackage(context, UI_PPSSPP_PACKAGE_CANDIDATES) ?: return false
    return resolvesPpssppBroadcast(context, packageName, PPSSPP_SET_HOST_OVERRIDE_ACTION)
        && resolvesPpssppBroadcast(context, packageName, PPSSPP_CLEAR_HOST_OVERRIDE_ACTION)
}

internal fun buildPatchedPpssppContent(content: String, proxyAddress: String): String =
    updatePpssppAchievementsSection(content) {
        put("AchievementsHost", proxyAddress)
        put("AchievementsChallengeMode", "False")
    }

internal fun buildRevertedPpssppContent(content: String, restoreHardcore: Boolean = false): String =
    updatePpssppAchievementsSection(content) {
        put("AchievementsHost", "")
        put("AchievementsChallengeMode", if (restoreHardcore) "True" else "False")
    }

internal fun isPpssppPatchedContent(content: String, proxyAddress: String): Boolean =
    extractPpssppAchievementValue(content, "AchievementsHost") == proxyAddress

internal fun detectPpssppHardcoreEnabled(content: String): Boolean =
    extractPpssppAchievementValue(content, "AchievementsChallengeMode")
        ?.equals("true", ignoreCase = true)
        ?: false

private fun broadcastPpssppHostOverride(context: Context): ConfigPatchResult? {
    val packageName = resolveInstalledPackage(context, UI_PPSSPP_PACKAGE_CANDIDATES) ?: return null
    if (!supportsPpssppBroadcastOverride(context)) {
        return null
    }

    context.sendBroadcast(
        Intent(PPSSPP_SET_HOST_OVERRIDE_ACTION)
            .setPackage(packageName)
            .putExtra(PPSSPP_HOST_OVERRIDE_EXTRA, proxyBase(proxyPort(context)))
    )
    return ConfigPatchResult(
        success = true,
        message = context.getString(R.string.ppsspp_patch_success),
        skippedNotInstalled = false
    )
}

private fun clearPpssppHostOverride(context: Context): ConfigPatchResult? {
    val packageName = resolveInstalledPackage(context, UI_PPSSPP_PACKAGE_CANDIDATES) ?: return null
    if (!supportsPpssppBroadcastOverride(context)) {
        return null
    }

    context.sendBroadcast(
        Intent(PPSSPP_CLEAR_HOST_OVERRIDE_ACTION)
            .setPackage(packageName)
    )
    return ConfigPatchResult(
        success = true,
        message = context.getString(R.string.ppsspp_revert_success),
        skippedNotInstalled = false
    )
}

private fun resolvesPpssppBroadcast(context: Context, packageName: String, action: String): Boolean {
    val intent = Intent(action).setPackage(packageName)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.queryBroadcastReceivers(intent, PackageManager.ResolveInfoFlags.of(0)).isNotEmpty()
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.queryBroadcastReceivers(intent, 0).isNotEmpty()
    }
}

private fun applyPpssppTransform(
    context: Context,
    treeUri: Uri?,
    transform: (String) -> String,
    strings: PpssppStrings,
    extractCredentials: Boolean,
    detectHardcore: Boolean
): ConfigPatchResult {
    if (treeUri == null) {
        return ConfigPatchResult(
            success = false,
            message = context.getString(R.string.ppsspp_saf_dialog_message),
            needsSafGrant = true
        )
    }

    val tree = DocumentFile.fromTreeUri(context, treeUri)
        ?: return ConfigPatchResult(
            success = false,
            message = context.getString(strings.unavailableError)
        )

    val iniFile = resolvePpssppIni(tree)
        ?: return ConfigPatchResult(
            success = false,
            message = context.getString(strings.configMissingInFolder),
            invalidSafGrant = true
        )

    return try {
        val original = context.contentResolver.openInputStream(iniFile.uri)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: return ConfigPatchResult(success = false, message = context.getString(R.string.patch_could_not_read, PPSSPP_INI_FILE))
        val credentials = if (extractCredentials) {
            extractPpssppCredentials(context, iniFile, original)
        } else {
            null
        }
        val hardcoreWasEnabled = if (detectHardcore) detectPpssppHardcoreEnabled(original) else false
        val transformed = transform(original)
        if (transformed == original) {
            ConfigPatchResult(
                success = true,
                message = context.getString(strings.noOpMessage),
                hardcoreWasEnabled = hardcoreWasEnabled,
                credentials = credentials
            )
        } else {
            context.contentResolver.openOutputStream(iniFile.uri, "wt")
                ?.use { it.write(transformed.toByteArray()) }
                ?: return ConfigPatchResult(success = false, message = context.getString(R.string.patch_could_not_write, PPSSPP_INI_FILE))
            ConfigPatchResult(
                success = true,
                message = context.getString(strings.successSaf),
                hardcoreWasEnabled = hardcoreWasEnabled,
                credentials = credentials
            )
        }
    } catch (e: Exception) {
        ConfigPatchResult(success = false, message = context.getString(strings.errorSaf, e.message))
    }
}

private fun extractPpssppCredentials(
    context: Context,
    iniFile: DocumentFile,
    iniContent: String
): ImportedCredentials? {
    val username = extractPpssppAchievementValue(iniContent, "AchievementsUserName")
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val tokenFile = iniFile.parentFile?.findFile("ppsspp_retroachievements.dat")
        ?.takeIf { it.exists() && it.isFile }
        ?: return null
    val token = context.contentResolver.openInputStream(tokenFile.uri)
        ?.bufferedReader()
        ?.use { it.readText().trim() }
        ?.takeIf { it.isNotBlank() }
        ?: return null
    return ImportedCredentials.Token(username = username, token = token)
}

private fun resolvePpssppIni(tree: DocumentFile): DocumentFile? =
    PPSSPP_SAF_ROOT_PATHS.firstNotNullOfOrNull { rootSegments ->
        val root = rootSegments.fold(tree as DocumentFile?) { current, segment -> current?.findFile(segment) }
            ?: return@firstNotNullOfOrNull null
        val systemDir = root.findFile(PPSSPP_SYSTEM_DIR)
        val iniFile = systemDir?.findFile(PPSSPP_INI_FILE)
        iniFile?.takeIf { it.exists() && it.isFile }
    }

private fun updatePpssppAchievementsSection(content: String, update: MutableMap<String, String>.() -> Unit): String {
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
        val additions = remaining.entries.map { (key, value) -> "$key = $value" }
        lines.addAll(sectionEnd, additions)
    }

    return lines.joinToString("\n")
}

private fun extractPpssppAchievementValue(content: String, key: String): String? {
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
