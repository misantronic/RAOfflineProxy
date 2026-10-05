package com.raofflineproxy.ui

import org.json.JSONObject

internal fun hasCredentialSource(
    hasCachedCredentials: Boolean,
    emulatorSupport: EmulatorSupport
): Boolean = hasCachedCredentials ||
    emulatorSupport.isEnabled(Emulator.RetroArch) ||
    emulatorSupport.isEnabled(Emulator.Dolphin) ||
    emulatorSupport.isEnabled(Emulator.Ppsspp)

internal fun needsLoginChoice(
    hasCachedCredentials: Boolean,
    emulatorSupport: EmulatorSupport,
    choiceSkipped: Boolean
): Boolean = !choiceSkipped && !hasCredentialSource(hasCachedCredentials, emulatorSupport)

internal fun needsLoginChoiceAfterImport(
    hasCredentialsAfterImport: Boolean,
    choiceSkipped: Boolean
): Boolean = !choiceSkipped && !hasCredentialsAfterImport

internal fun shouldPromptRejectedToken(
    rejected: Boolean,
    promptActive: Boolean,
    token: String,
    lastPromptedToken: String?
): Boolean = rejected && !promptActive && token != lastPromptedToken

internal fun shouldReportAuthFailure(
    rejected: Boolean,
    token: String,
    lastPromptedToken: String?
): Boolean = !(rejected && token == lastPromptedToken)

internal fun isInvalidCredentialsResponse(body: String): Boolean =
    runCatching { JSONObject(body).optString("Code") == "invalid_credentials" }.getOrDefault(false)
