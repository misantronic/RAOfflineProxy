package com.raofflineproxy.donation

import android.util.Log
import com.raofflineproxy.sharedHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private const val SUPPORT_PAYMENT_BASE_URL = "https://ud63psmdb5.execute-api.eu-central-1.amazonaws.com/support"
private const val TAG = "RAProxy/DonationManager"

data class SubscriptionCheckout(
    val clientSecret: String,
    val customerId: String,
    val ephemeralKey: String
)

object DonationManager {

    fun createPaymentIntent(amountCents: Int): Result<String> = runCatching {
        val json = post("$SUPPORT_PAYMENT_BASE_URL/payment-intent", JSONObject().put("amount", amountCents))
        json.getString("clientSecret")
    }.onFailure { error ->
        Log.e(TAG, "createPaymentIntent failed: ${error.message}", error)
    }

    fun createSubscription(amountCents: Int): Result<SubscriptionCheckout> = runCatching {
        val json = post("$SUPPORT_PAYMENT_BASE_URL/subscription", JSONObject().put("amount", amountCents))
        SubscriptionCheckout(
            clientSecret = json.getString("clientSecret"),
            customerId = json.getString("customerId"),
            ephemeralKey = json.getString("ephemeralKey")
        )
    }.onFailure { error ->
        Log.e(TAG, "createSubscription failed: ${error.message}", error)
    }

    fun syncCustomerEmail(paymentIntentId: String, customerId: String): Result<Unit> = runCatching {
        post(
            "$SUPPORT_PAYMENT_BASE_URL/subscription/sync-email",
            JSONObject()
                .put("paymentIntentId", paymentIntentId)
                .put("customerId", customerId)
        )
        Unit
    }.onFailure { error ->
        Log.e(TAG, "syncCustomerEmail failed: ${error.message}", error)
    }

    fun requestEmailInvoice(amountCents: Int, frequency: String, email: String): Result<Unit> = runCatching {
        post(
            "$SUPPORT_PAYMENT_BASE_URL/email-invoice",
            JSONObject()
                .put("amount", amountCents)
                .put("frequency", frequency)
                .put("email", email)
        )
        Unit
    }.onFailure { error ->
        Log.e(TAG, "requestEmailInvoice failed: ${error.message}", error)
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
