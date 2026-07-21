package com.raofflineproxy.diagnostics

import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.core.content.FileProvider
import com.raofflineproxy.redactFormBody
import com.raofflineproxy.redactTokens
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MAX_LOG_LINES = 20000
private const val LOG_FILE_DIR = "logs"

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

    fun writeLogFile(context: Context, content: String): File {
        val dir = File(context.cacheDir, LOG_FILE_DIR).apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "raofflineproxy-log-$timestamp.txt")
        file.writeText(content)
        return file
    }

    fun shareLogFileIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(sendIntent, null)
    }
}
