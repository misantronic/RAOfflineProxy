package com.raofflineproxy.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val LAST_CRASH_FILE = "last-crash.txt"
private const val MAX_STACK_LINES = 80
private const val MAX_EXIT_REASONS = 5

// The log upload only sees the current process's logcat, so a crash that killed the previous
// process would otherwise leave no trace in a support log.
object CrashReport {

    fun install(context: Context) {
        val file = File(context.filesDir, LAST_CRASH_FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file.writeText(describeCrash(thread.name, error, System.currentTimeMillis())) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun describe(context: Context): String = buildString {
        appendLine("== Previous app exits")
        appendLine(previousExits(context))
        appendLine("== Last crash")
        appendLine(
            runCatching { File(context.filesDir, LAST_CRASH_FILE).readText() }
                .getOrNull()
                ?.let(LogExporter::redactText)
                ?: "none recorded"
        )
    }

    internal fun describeCrash(threadName: String, error: Throwable, at: Long): String = buildString {
        appendLine("${formatTime(at)} thread=$threadName")
        error.stackTraceToString().lineSequence().take(MAX_STACK_LINES).forEach(::appendLine)
    }

    private fun previousExits(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "unavailable before Android 11"
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return "unavailable"
        val exits = runCatching {
            activityManager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXIT_REASONS)
        }.getOrDefault(emptyList())
        if (exits.isEmpty()) return "none recorded"
        return exits.joinToString("\n") { exit ->
            "${formatTime(exit.timestamp)} ${exitReasonName(exit.reason)} " +
                "importance=${exit.importance} pss=${exit.pss / 1024}MB rss=${exit.rss / 1024}MB " +
                (exit.description ?: "")
        }
    }

    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native-crash"
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low-memory"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive-resource-usage"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exit-self"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user-requested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user-stopped"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission-change"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency-died"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization-failure"
        ApplicationExitInfo.REASON_OTHER -> "other"
        else -> "unknown($reason)"
    }

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(millis))
}
