package com.raofflineproxy.diagnostics

import android.util.Log
import com.raofflineproxy.sharedHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val REQUEST_UPLOAD_URL = "https://ud63psmdb5.execute-api.eu-central-1.amazonaws.com/logs/request-upload"
private const val TAG = "RAProxy/LogUploader"

object LogUploader {

    fun uploadLogs(): Result<String> = runCatching {
        val zipBytes = zipLogs(LogExporter.captureRecentLogs())

        val requestUploadResponse = sharedHttpClient.newCall(
            Request.Builder()
                .url(REQUEST_UPLOAD_URL)
                .post("".toRequestBody("application/json".toMediaType()))
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
