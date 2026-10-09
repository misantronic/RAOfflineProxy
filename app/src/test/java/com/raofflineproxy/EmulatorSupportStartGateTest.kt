package com.raofflineproxy

import com.raofflineproxy.ui.EmulatorSupport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulatorSupportStartGateTest {
    @Test
    fun noEmulatorAndNoArgosy_cannotStart() {
        assertFalse(EmulatorSupport.NONE.canStartProxy)
    }

    @Test
    fun argosyOnly_canStartWithoutEnabledEmulator() {
        val support = EmulatorSupport(EmulatorSupport.NONE.states, argosyInstalled = true)
        assertFalse(support.hasAnyEnabled)
        assertTrue(support.canStartProxy)
    }
}
