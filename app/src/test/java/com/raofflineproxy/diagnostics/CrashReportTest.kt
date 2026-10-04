package com.raofflineproxy.diagnostics

import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportTest {

    @Test
    fun describeCrash_namesThreadAndException() {
        val text = CrashReport.describeCrash("main", OutOfMemoryError("Failed to allocate"), 0L)

        assertTrue(text.lines().first().endsWith("thread=main"))
        assertTrue(text.contains("java.lang.OutOfMemoryError: Failed to allocate"))
    }

    @Test
    fun describeCrash_capsStackTraceLength() {
        val error = RuntimeException("deep").apply {
            stackTrace = Array(500) { StackTraceElement("Cls", "method$it", "File.kt", it) }
        }

        val text = CrashReport.describeCrash("worker", error, 0L)

        assertTrue(text.lines().size <= 82)
    }
}
