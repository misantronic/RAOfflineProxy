package com.raofflineproxy.donation

import android.util.Log
import com.raofflineproxy.BuildConfig
import com.raofflineproxy.PROXY_UA_TAG
import com.raofflineproxy.proxy.LoginCredentials
import com.raofflineproxy.sharedHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private const val SUPPORT_PAYMENT_BASE_URL = "https://ud63psmdb5.execute-api.eu-central-1.amazonaws.com/support"
private const val TAG = "RAProxy/DonationManager"
// The same User-Agent format the app itself sends when calling RA directly (NetworkConstants.kt)
// — RA's API rejects requests from user agents it doesn't recognize, so the backend needs to
// present as this same client when verifying RA credentials on the app's behalf.
private val RA_USER_AGENT = "$PROXY_UA_TAG/${BuildConfig.VERSION_NAME}"

object DonationManager {

    fun requestEmailInvoice(
        amountCents: Int,
        frequency: String,
        email: String,
        raCredentials: LoginCredentials?,
        test: Boolean = false
    ): Result<Unit> = runCatching {
        val body = JSONObject()
            .put("amount", amountCents)
            .put("frequency", frequency)
            .put("email", email)
        if (raCredentials != null) {
            body.put("raUsername", raCredentials.user)
            body.put("raToken", raCredentials.token)
            body.put("raUserAgent", RA_USER_AGENT)
        }
        if (test) body.put("test", true)
        post("$SUPPORT_PAYMENT_BASE_URL/email-invoice", body)
        Unit
    }.onFailure { error ->
        Log.e(TAG, "requestEmailInvoice failed: ${error.message}", error)
    }

    fun checkSubscriptionStatus(raCredentials: LoginCredentials): Result<Boolean> = runCatching {
        val json = post(
            "$SUPPORT_PAYMENT_BASE_URL/subscription/status",
            JSONObject()
                .put("username", raCredentials.user)
                .put("token", raCredentials.token)
                .put("userAgent", RA_USER_AGENT)
        )
        json.optBoolean("hasActiveSubscription", false)
    }.onFailure { error ->
        Log.e(TAG, "checkSubscriptionStatus failed: ${error.message}", error)
    }

    fun getManageSubscriptionUrl(raCredentials: LoginCredentials): Result<String> = runCatching {
        val json = post(
            "$SUPPORT_PAYMENT_BASE_URL/subscription/portal",
            JSONObject()
                .put("username", raCredentials.user)
                .put("token", raCredentials.token)
                .put("userAgent", RA_USER_AGENT)
        )
        json.getString("url")
    }.onFailure { error ->
        Log.e(TAG, "getManageSubscriptionUrl failed: ${error.message}", error)
    }

    private fun post(url: String, body: JSONObject): JSONObject {
        val response = sharedHttpClient.newCall(
            Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
        ).execute()

        val responseBody = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            error("Request failed (HTTP ${response.code}): ${responseBody.take(512)}")
        }

        return try {
            JSONObject(responseBody)
        } catch (e: Exception) {
            error("Malformed response: ${responseBody.take(512)}")
        }
    }
}
