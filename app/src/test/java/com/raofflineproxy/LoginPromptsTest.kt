package com.raofflineproxy

import com.raofflineproxy.ui.Emulator
import com.raofflineproxy.ui.EmulatorState
import com.raofflineproxy.ui.EmulatorSupport
import com.raofflineproxy.ui.isInvalidCredentialsResponse
import com.raofflineproxy.ui.needsLoginChoice
import com.raofflineproxy.ui.needsLoginChoiceAfterImport
import com.raofflineproxy.ui.shouldPromptRejectedToken
import com.raofflineproxy.ui.shouldReportAuthFailure
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginPromptsTest {
    private fun supportWithEnabled(vararg enabled: Emulator) = EmulatorSupport(
        Emulator.entries.map { EmulatorState(it, installed = it in enabled, enabled = it in enabled) }
    )

    private val broadcastOnly = supportWithEnabled(
        *Emulator.entries.filter {
            it !in listOf(Emulator.RetroArch, Emulator.Dolphin, Emulator.Ppsspp)
        }.toTypedArray()
    )

    @Test
    fun loginChoice_isOfferedWithoutALoginOrImportableEmulator() {
        assertTrue(needsLoginChoice(false, broadcastOnly, choiceSkipped = false))
    }

    @Test
    fun loginChoice_isNotOfferedAfterTheUserStartedWithout() {
        assertFalse(needsLoginChoice(false, broadcastOnly, choiceSkipped = true))
    }

    @Test
    fun loginChoice_isNotOfferedWithACachedLogin() {
        assertFalse(needsLoginChoice(true, broadcastOnly, choiceSkipped = false))
    }

    @Test
    fun loginChoice_isNotOfferedBeforeAnImportableEmulatorHadItsChance() {
        assertFalse(needsLoginChoice(false, supportWithEnabled(Emulator.RetroArch), choiceSkipped = false))
    }

    @Test
    fun loginChoiceAfterImport_isOfferedWhenTheImportFoundNothing() {
        assertTrue(needsLoginChoiceAfterImport(hasCredentialsAfterImport = false, choiceSkipped = false))
    }

    @Test
    fun loginChoiceAfterImport_isNotOfferedWhenTheImportFoundALogin() {
        assertFalse(needsLoginChoiceAfterImport(hasCredentialsAfterImport = true, choiceSkipped = false))
    }

    @Test
    fun loginChoiceAfterImport_isNotOfferedAfterTheUserStartedWithout() {
        assertFalse(needsLoginChoiceAfterImport(hasCredentialsAfterImport = false, choiceSkipped = true))
    }

    @Test
    fun rejectedToken_promptsTheFirstTime() {
        assertTrue(shouldPromptRejectedToken(rejected = true, promptActive = false, token = "a", lastPromptedToken = null))
    }

    @Test
    fun rejectedToken_doesNotPromptTwiceForTheSameToken() {
        assertFalse(shouldPromptRejectedToken(rejected = true, promptActive = false, token = "a", lastPromptedToken = "a"))
    }

    @Test
    fun rejectedToken_promptsAgainForANewRejectedToken() {
        assertTrue(shouldPromptRejectedToken(rejected = true, promptActive = false, token = "b", lastPromptedToken = "a"))
    }

    @Test
    fun rejectedToken_doesNotStackOnAnOpenPrompt() {
        assertFalse(shouldPromptRejectedToken(rejected = true, promptActive = true, token = "a", lastPromptedToken = null))
    }

    @Test
    fun acceptedOrFailedRequests_doNotPrompt() {
        assertFalse(shouldPromptRejectedToken(rejected = false, promptActive = false, token = "a", lastPromptedToken = null))
    }

    @Test
    fun authFailureSnackbar_isShownForTheFirstRejection() {
        assertTrue(shouldReportAuthFailure(rejected = true, token = "a", lastPromptedToken = null))
    }

    @Test
    fun authFailureSnackbar_isSuppressedForATokenWeAlreadyPromptedFor() {
        assertFalse(shouldReportAuthFailure(rejected = true, token = "a", lastPromptedToken = "a"))
    }

    @Test
    fun otherFailures_alwaysShowTheSnackbar() {
        assertTrue(shouldReportAuthFailure(rejected = false, token = "a", lastPromptedToken = "a"))
    }

    @Test
    fun invalidCredentialsBody_isARejection() {
        assertTrue(isInvalidCredentialsResponse("""{"Success":false,"Status":401,"Code":"invalid_credentials"}"""))
    }

    @Test
    fun otherUnsuccessfulBodies_areNotARejection() {
        assertFalse(isInvalidCredentialsResponse("""{"Success":false,"Error":"Unknown game"}"""))
        assertFalse(isInvalidCredentialsResponse("""{"Success":false,"Code":"not_found"}"""))
    }

    @Test
    fun unparseableBodies_areNotARejection() {
        assertFalse(isInvalidCredentialsResponse("not json"))
        assertFalse(isInvalidCredentialsResponse(""))
    }
}
