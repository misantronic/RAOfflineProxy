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
private const val SUPPORT_SUBMIT_URL = "https://ud63psmdb5.execute-api.eu-central-1.amazonaws.com/support/submit"
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

    // Posted to the same endpoint the docs support form uses, so a user who adds details while
    // sending logs lands in the same Discord channel as a website submission.
    fun submitSupportRequest(context: Context, logId: String, email: String, message: String): Result<Unit> =
        runCatching {
            val info = deviceInfo(context)
            val payload = JSONObject().apply {
                put("email", email)
                put("system", "Android")
                put("device", info.device)
                put("os_version", info.osVersion)
                put("app_version", info.appVersion)
                if (info.enabledEmulators.isNotEmpty()) {
                    put("emulator", info.enabledEmulators.joinToString(", "))
                }
                put("log_id", logId)
                put("message", message)
            }

            val response = sharedHttpClient.newCall(
                Request.Builder()
                    .url(SUPPORT_SUBMIT_URL)
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute()

            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                error("Support request failed (HTTP ${response.code}): ${body.take(512)}")
            }
        }.onFailure { error ->
            Log.e(TAG, "submitSupportRequest failed: ${error.message}", error)
        }

    private data class DeviceInfo(
        val device: String,
        val osVersion: String,
        val appVersion: String,
        val enabledEmulators: List<String>
    )

    private fun deviceInfo(context: Context): DeviceInfo {
        val emulators = loadEmulatorSupport(context)
        val enabledEmulators = buildList {
            if (emulators.retroArchEnabled) add("RetroArch")
            if (emulators.dolphinEnabled) add("Dolphin")
            if (emulators.ppssppEnabled) add("PPSSPP")
            if (emulators.armsx2Enabled) add("ARMSX2")
            if (emulators.flycastEnabled) add("Flycast")
            if (emulators.watermelonDsEnabled) add("WatermelonDS")
            if (emulators.mupen64Enabled) add("Mupen64Plus")
            if (emulators.emuCoreXEnabled) add("EmuCoreX")
            if (emulators.armsx1Enabled) add("ARMSX1")
        }

        return DeviceInfo(
            device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            osVersion = androidVersionLabel(),
            appVersion = BuildConfig.VERSION_NAME,
            enabledEmulators = enabledEmulators
        )
    }

    // Submitted alongside the log so the support form can skip asking for this again once the
    // user provides a Log ID. Best-effort: any field that can't be determined is just omitted.
    private fun uploadMetadata(context: Context): JSONObject {
        val info = deviceInfo(context)
        return JSONObject().apply {
            put("system", "Android")
            put("device", info.device)
            put("os_version", info.osVersion)
            put("app_version", info.appVersion)
            if (info.enabledEmulators.isNotEmpty()) {
                put("emulator", JSONArray(info.enabledEmulators))
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
