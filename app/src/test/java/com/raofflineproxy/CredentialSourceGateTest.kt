package com.raofflineproxy

import com.raofflineproxy.ui.Emulator
import com.raofflineproxy.ui.EmulatorState
import com.raofflineproxy.ui.EmulatorSupport
import com.raofflineproxy.ui.hasCredentialSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialSourceGateTest {
    private fun supportWithEnabled(vararg enabled: Emulator) = EmulatorSupport(
        Emulator.entries.map { EmulatorState(it, installed = it in enabled, enabled = it in enabled) }
    )

    private val broadcastOnly = Emulator.entries.filter {
        it !in listOf(Emulator.RetroArch, Emulator.Dolphin, Emulator.Ppsspp)
    }

    @Test
    fun cachedCredentials_alwaysPass() {
        assertTrue(hasCredentialSource(true, EmulatorSupport.NONE))
        assertTrue(hasCredentialSource(true, supportWithEnabled(*broadcastOnly.toTypedArray())))
    }

    @Test
    fun noCachedCredentials_withBroadcastOnlyEmulators_requiresLogin() {
        assertFalse(hasCredentialSource(false, supportWithEnabled(*broadcastOnly.toTypedArray())))
    }

    @Test
    fun noCachedCredentials_withNoEnabledEmulator_requiresLogin() {
        assertFalse(hasCredentialSource(false, EmulatorSupport.NONE))
    }

    @Test
    fun noCachedCredentials_withRetroArchEnabled_triesImport() {
        assertTrue(hasCredentialSource(false, supportWithEnabled(Emulator.RetroArch)))
    }

    @Test
    fun noCachedCredentials_withDolphinEnabled_triesImport() {
        assertTrue(hasCredentialSource(false, supportWithEnabled(Emulator.Dolphin)))
    }

    @Test
    fun noCachedCredentials_withPpssppEnabled_triesImport() {
        assertTrue(hasCredentialSource(false, supportWithEnabled(Emulator.Ppsspp)))
    }

    @Test
    fun noCachedCredentials_mixedBroadcastAndImportable_triesImport() {
        assertTrue(hasCredentialSource(false, supportWithEnabled(Emulator.Dolphin, *broadcastOnly.toTypedArray())))
    }
}
