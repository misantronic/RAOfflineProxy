package com.raofflineproxy.ui

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.raofflineproxy.R
import com.raofflineproxy.proxyBase
import com.raofflineproxy.proxyPort

internal const val DUCKSTATION_SETTINGS_FILE = "settings.ini"
private const val DUCKSTATION_CHEEVOS_SECTION = "Cheevos"
private const val DUCKSTATION_HOST_KEY = "Host"
private const val DUCKSTATION_CHALLENGE_MODE_KEY = "ChallengeMode"
private const val DUCKSTATION_USERNAME_KEY = "Username"
private const val DUCKSTATION_TOKEN_KEY = "Token"

internal val DUCKSTATION_SHIZUKU_SOURCE_CANDIDATES by lazy {
    Emulator.DuckStation.packageCandidates.map { packageName ->
        "/storage/emulated/0/Android/data/$packageName/files/$DUCKSTATION_SETTINGS_FILE"
    }
}

private val DUCKSTATION_SAF_SETTINGS_PATHS = Emulator.DuckStation.packageCandidates.map { packageName ->
    listOf(packageName, "files", DUCKSTATION_SETTINGS_FILE)
} + listOf(
    listOf("files", DUCKSTATION_SETTINGS_FILE),
    listOf(DUCKSTATION_SETTINGS_FILE)
)

private data class DuckStationStrings(
    val noOpMessage: Int,
    val successSaf: Int,
    val errorSaf: Int,
    val configMissingInFolder: Int,
    val unavailableError: Int
)

private val DUCKSTATION_PATCH_STRINGS = DuckStationStrings(
    noOpMessage = R.string.duckstation_patch_already_configured,
    successSaf = R.string.duckstation_patch_success,
    errorSaf = R.string.duckstation_patch_error_saf,
    configMissingInFolder = R.string.duckstation_patch_cfg_not_in_folder,
    unavailableError = R.string.duckstation_patch_error_unavailable
)

private val DUCKSTATION_REVERT_STRINGS = DuckStationStrings(
    noOpMessage = R.string.duckstation_revert_already_reverted,
    successSaf = R.string.duckstation_revert_success,
    errorSaf = R.string.duckstation_revert_error_saf,
    configMissingInFolder = R.string.duckstation_patch_cfg_not_in_folder,
    unavailableError = R.string.duckstation_revert_error_unavailable
)

internal fun isDuckStationInstalled(context: Context): Boolean =
    resolveInstalledPackage(context, Emulator.DuckStation.packageCandidates) != null

fun patchDuckStationCfg(context: Context, treeUri: Uri?): ConfigPatchResult {
    if (!isDuckStationInstalled(context)) {
        return ConfigPatchResult(success = true, message = "DuckStation not installed.", skippedNotInstalled = true)
    }

    return applyDuckStationTransform(
        context = context,
        treeUri = treeUri,
        transform = { buildPatchedDuckStationContent(it, proxyBase(proxyPort(context))) },
        strings = DUCKSTATION_PATCH_STRINGS,
        detectHardcore = true,
        extractCredentials = true
    )
}

fun revertDuckStationCfg(context: Context, treeUri: Uri?, restoreHardcore: Boolean = false): ConfigPatchResult {
    if (!isDuckStationInstalled(context)) {
        return ConfigPatchResult(success = true, message = "DuckStation not installed.", skippedNotInstalled = true)
    }

    return applyDuckStationTransform(
        context = context,
        treeUri = treeUri,
        transform = { buildRevertedDuckStationContent(it, restoreHardcore) },
        strings = DUCKSTATION_REVERT_STRINGS,
        detectHardcore = false,
        extractCredentials = false
    )
}

fun checkIsDuckStationPatched(context: Context, treeUri: Uri?): Boolean {
    val tree = treeUri?.let { DocumentFile.fromTreeUri(context, it) } ?: return false
    val settingsFile = resolveDuckStationSettings(tree) ?: return false
    val content = runCatching {
        context.contentResolver.openInputStream(settingsFile.uri)
            ?.bufferedReader()
            ?.use { it.readText() }
    }.getOrNull() ?: return false
    return isDuckStationPatchedContent(content, proxyBase(proxyPort(context)))
}

internal fun validateDuckStationRoot(context: Context, treeUri: Uri): Boolean {
    val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return false
    return resolveDuckStationSettings(tree) != null
}

internal fun buildPatchedDuckStationContent(content: String, proxyAddress: String): String =
    updateDuckStationCheevosSection(content) {
        put(DUCKSTATION_HOST_KEY, proxyAddress)
        put(DUCKSTATION_CHALLENGE_MODE_KEY, "false")
    }

internal fun buildRevertedDuckStationContent(content: String, restoreHardcore: Boolean = false): String =
    updateDuckStationCheevosSection(content) {
        put(DUCKSTATION_HOST_KEY, "")
        put(DUCKSTATION_CHALLENGE_MODE_KEY, if (restoreHardcore) "true" else "false")
    }

internal fun isDuckStationPatchedContent(content: String, proxyAddress: String): Boolean =
    extractDuckStationCheevosValue(content, DUCKSTATION_HOST_KEY) == proxyAddress

internal fun extractDuckStationCredentials(content: String): ImportedCredentials? {
    val username = extractDuckStationCheevosValue(content, DUCKSTATION_USERNAME_KEY)
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val token = extractDuckStationCheevosValue(content, DUCKSTATION_TOKEN_KEY)
        ?.takeIf { it.isNotBlank() }
        ?: return null
    return ImportedCredentials.Token(username = username, token = token)
}

internal fun detectDuckStationHardcoreEnabled(content: String): Boolean =
    extractDuckStationCheevosValue(content, DUCKSTATION_CHALLENGE_MODE_KEY)
        ?.equals("true", ignoreCase = true)
        ?: false

private fun applyDuckStationTransform(
    context: Context,
    treeUri: Uri?,
    transform: (String) -> String,
    strings: DuckStationStrings,
    detectHardcore: Boolean,
    extractCredentials: Boolean
): ConfigPatchResult {
    if (treeUri == null) {
        return ConfigPatchResult(
            success = false,
            message = context.getString(R.string.duckstation_saf_dialog_message),
            needsSafGrant = true
        )
    }

    val tree = DocumentFile.fromTreeUri(context, treeUri)
        ?: return ConfigPatchResult(success = false, message = context.getString(strings.unavailableError))

    val settingsFile = resolveDuckStationSettings(tree)
        ?: return ConfigPatchResult(
            success = false,
            message = context.getString(strings.configMissingInFolder),
            invalidSafGrant = true
        )

    return try {
        val original = context.contentResolver.openInputStream(settingsFile.uri)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: return ConfigPatchResult(
                success = false,
                message = context.getString(R.string.patch_could_not_read, DUCKSTATION_SETTINGS_FILE)
            )
        val hardcoreWasEnabled = if (detectHardcore) detectDuckStationHardcoreEnabled(original) else false
        val credentials = if (extractCredentials) extractDuckStationCredentials(original) else null
        val transformed = transform(original)
        if (transformed == original) {
            ConfigPatchResult(
                success = true,
                message = context.getString(strings.noOpMessage),
                hardcoreWasEnabled = hardcoreWasEnabled,
                credentials = credentials
            )
        } else {
            context.contentResolver.openOutputStream(settingsFile.uri, "wt")
                ?.use { it.write(transformed.toByteArray()) }
                ?: return ConfigPatchResult(
                    success = false,
                    message = context.getString(R.string.patch_could_not_write, DUCKSTATION_SETTINGS_FILE)
                )
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

private fun resolveDuckStationSettings(tree: DocumentFile): DocumentFile? =
    DUCKSTATION_SAF_SETTINGS_PATHS.firstNotNullOfOrNull { segments ->
        segments
            .fold(tree as DocumentFile?) { current, segment -> current?.findFile(segment) }
            ?.takeIf { it.exists() && it.isFile }
    }

private fun updateDuckStationCheevosSection(content: String, update: MutableMap<String, String>.() -> Unit): String {
    val replacements = linkedMapOf<String, String>().apply(update)
    val lines = content.split('\n').toMutableList()
    val header = "[$DUCKSTATION_CHEEVOS_SECTION]"
    val sectionIndex = lines.indexOfFirst { it.trim() == header }

    if (sectionIndex == -1) {
        val body = replacements.entries.joinToString("\n") { (key, value) -> "$key = $value" }
        return content.trimEnd() + "\n\n$header\n$body\n"
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
        lines[index] = "${lines[index].substringBefore('=')}= $value".trimEnd()
    }

    if (remaining.isNotEmpty()) {
        var insertAt = sectionEnd
        while (insertAt > sectionIndex + 1 && lines[insertAt - 1].isBlank()) insertAt--
        lines.addAll(insertAt, remaining.entries.map { (key, value) -> "$key = $value" })
    }

    return lines.joinToString("\n")
}

private fun extractDuckStationCheevosValue(content: String, key: String): String? {
    var inSection = false

    for (line in content.lines()) {
        val trimmed = line.trim()
        if (trimmed.startsWith('[') && trimmed.endsWith(']')) {
            inSection = trimmed == "[$DUCKSTATION_CHEEVOS_SECTION]"
            continue
        }
        if (!inSection) continue

        val separator = trimmed.indexOf('=')
        if (separator == -1) continue
        if (trimmed.substring(0, separator).trim() == key) {
            return trimmed.substring(separator + 1).trim()
        }
    }

    return null
}
