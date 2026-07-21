package com.raofflineproxy.diagnostics

import android.os.Process
import com.raofflineproxy.redactFormBody
import com.raofflineproxy.redactTokens

private const val MAX_LOG_LINES = 20000

private val JSON_SECRET_FIELD_REGEX =
    Regex("""("(?:Token|Password)"\s*:\s*")[^"]*(")""", RegexOption.IGNORE_CASE)

// Matches the `time` header, e.g. "07-21 10:15:23.123 D/RAProxy/Hash( 1234): message"
private val TIME_HEADER_REGEX =
    Regex("""^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+)\s+[VDIWEF]/([^(]+)\(\s*\d+\):(.*)$""")

private const val TAG_PREFIX = "RAProxy"

object LogExporter {

    fun captureRecentLogs(maxLines: Int = MAX_LOG_LINES): String {
        val pid = Process.myPid()
        return try {
            val process = ProcessBuilder("logcat", "-d", "-v", "time", "--pid=$pid")
                .redirectErrorStream(true)
                .start()
            val recentLines = ArrayDeque<String>()
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val stripped = stripMetadata(line) ?: return@forEach
                    if (recentLines.size == maxLines) recentLines.removeFirst()
                    recentLines.addLast(redactLine(stripped))
                }
            }
            process.waitFor()
            recentLines.joinToString("\n")
        } catch (e: Exception) {
            "Failed to capture logs: ${e.message}"
        }
    }

    // Drops the priority and PID, keeping only "date time tag: message" for lines tagged RAProxy*.
    internal fun stripMetadata(line: String): String? {
        val match = TIME_HEADER_REGEX.find(line) ?: return null
        val (dateTime, tag, message) = match.destructured
        val trimmedTag = tag.trim()
        if (!trimmedTag.startsWith(TAG_PREFIX)) return null
        return "$dateTime $trimmedTag:$message"
    }

    private fun redactLine(line: String): String {
        val withoutUrlSecrets = redactFormBody(redactTokens(line))
        return JSON_SECRET_FIELD_REGEX.replace(withoutUrlSecrets) { "${it.groupValues[1]}<redacted>${it.groupValues[2]}" }
    }
}
