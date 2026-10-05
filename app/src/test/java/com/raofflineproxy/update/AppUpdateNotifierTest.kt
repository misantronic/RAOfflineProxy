package com.raofflineproxy.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateNotifierTest {
    private val dayMs = 24L * 60 * 60 * 1000
    private val now = 10 * dayMs

    @Test
    fun isAppUpdatePromptDue_true_whenNeverPrompted() {
        assertTrue(isAppUpdatePromptDue(0L, now))
    }

    @Test
    fun isAppUpdatePromptDue_false_withinADay() {
        assertFalse(isAppUpdatePromptDue(now - dayMs + 1, now))
    }

    @Test
    fun isAppUpdatePromptDue_true_afterADay() {
        assertTrue(isAppUpdatePromptDue(now - dayMs, now))
    }
}
