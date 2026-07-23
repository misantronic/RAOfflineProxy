package com.raofflineproxy.diagnostics

import android.content.Context
import android.os.Build
import android.util.Log
import com.raofflineproxy.BuildConfig
import com.raofflineproxy.sharedHttpClient
import com.raofflineproxy.ui.loadEmulatorSupport
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val REQUEST_UPLOAD_URL = "https://ud63psmdb5.execute-api.eu-central-1.amazonaws.com/logs/request-upload"
private const val TAG = "RAProxy/LogUploader"

object LogUploader {

    fun uploadLogs(context: Context): Result<String> = runCatching {
        val zipBytes = zipLogs(LogExporter.captureRecentLogs())

        val requestUploadResponse = sharedHttpClient.newCall(
            Request.Builder()
                .url(REQUEST_UPLOAD_URL)
                .post(uploadMetadata(context).toString().toRequestBody("application/json".toMediaType()))
                .build()
        ).execute()

        val requestUploadBody = requestUploadResponse.body?.string().orEmpty()
        if (!requestUploadResponse.isSuccessful) {
            error("Could not request an upload URL (HTTP ${requestUploadResponse.code}): ${requestUploadBody.take(512)}")
        }

        val json = try {
            JSONObject(requestUploadBody)
        } catch (e: Exception) {
            error("Malformed request-upload response: ${requestUploadBody.take(512)}")
        }
        val id = json.getString("id")
        val uploadUrl = json.getString("uploadUrl")

        val putResponse = sharedHttpClient.newCall(
            Request.Builder()
                .url(uploadUrl)
                .put(zipBytes.toRequestBody("application/zip".toMediaType()))
                .build()
        ).execute()

        if (!putResponse.isSuccessful) {
            val putBody = putResponse.body?.string().orEmpty()
            error("Upload failed (HTTP ${putResponse.code}): ${putBody.take(512)}")
        }

        id
    }.onFailure { error ->
        Log.e(TAG, "uploadLogs failed: ${error.message}", error)
    }

    // Submitted alongside the log so the support form can skip asking for this again once the
    // user provides a Log ID. Best-effort: any field that can't be determined is just omitted.
    private fun uploadMetadata(context: Context): JSONObject {
        val emulators = loadEmulatorSupport(context)
        val enabledEmulators = buildList {
            if (emulators.retroArchEnabled) add("RetroArch")
            if (emulators.dolphinEnabled) add("Dolphin")
            if (emulators.ppssppEnabled) add("PPSSPP")
            if (emulators.armsx2Enabled) add("ARMSX2")
            if (emulators.flycastEnabled) add("Flycast")
            if (emulators.melonDualDsEnabled) add("melonDualDS")
            if (emulators.mupen64Enabled) add("Mupen64Plus")
            if (emulators.emuCoreXEnabled) add("EmuCoreX")
        }

        return JSONObject().apply {
            put("system", "Android")
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            put("os_version", androidVersionLabel())
            put("app_version", BuildConfig.VERSION_NAME)
            if (enabledEmulators.isNotEmpty()) {
                put("emulator", JSONArray(enabledEmulators))
            }
        }
    }

    private fun androidVersionLabel(): String =
        Build.VERSION.RELEASE?.takeIf { it.isNotBlank() } ?: Build.VERSION.SDK_INT.toString()

    private fun zipLogs(content: String): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            zip.putNextEntry(ZipEntry("raofflineproxy-log.txt"))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return buffer.toByteArray()
    }
}
