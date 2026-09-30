package com.raofflineproxy.usage

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.raofflineproxy.PrefsConstants
import com.raofflineproxy.proxy.QueueDrainResult
import okhttp3.Call
import okhttp3.Response
import java.io.IOException

/** Persists [UsageCounters] between reports. Nothing is counted unless the user opted in. */
internal object UsageStats {
    private const val TAG = "RAProxy/Usage"
    private const val PREFS_NAME = "usage_stats"
    private const val KEY_LAST_REPORTED_AT = "last_reported_at"

    @Volatile
    private var appContext: Context? = null

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    fun recordRaRequest(source: RaRequestSource, statusCode: Int?, now: Long = System.currentTimeMillis()) {
        update { it.withRequest(source, statusCode, now) }
    }

    fun recordBatch(result: QueueDrainResult, durationMs: Long) {
        update { it.withBatch(result, durationMs) }
    }

    @Synchronized
    fun snapshot(context: Context): UsageCounters = load(prefs(context))

    @Synchronized
    fun commitReport(context: Context, reported: Map<String, Long>, reportedAt: Long) {
        val prefs = prefs(context)
        save(prefs, load(prefs).afterReport(reported), lastReportedAt = reportedAt)
    }

    fun lastReportedAt(context: Context): Long = prefs(context).getLong(KEY_LAST_REPORTED_AT, 0L)

    @Synchronized
    fun clear(context: Context) {
        prefs(context).edit { clear() }
    }

    // Runs on proxy and caching request paths: counting must never make a request fail.
    @Synchronized
    private fun update(transform: (UsageCounters) -> UsageCounters) {
        val context = appContext ?: return
        runCatching {
            if (PrefsConstants.loadUsageStatsConsent(context) != true) return
            val prefs = prefs(context)
            save(prefs, transform(load(prefs)), lastReportedAt = prefs.getLong(KEY_LAST_REPORTED_AT, 0L))
        }.onFailure { error -> Log.w(TAG, "Usage counter update failed: ${error.message}") }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun load(prefs: SharedPreferences): UsageCounters = UsageCounters(
        prefs.all
            .filterKeys { it != KEY_LAST_REPORTED_AT }
            .mapNotNull { (key, value) -> (value as? Long)?.let { key to it } }
            .toMap()
    )

    private fun save(prefs: SharedPreferences, counters: UsageCounters, lastReportedAt: Long) {
        prefs.edit {
            clear()
            counters.values.forEach { (key, value) -> putLong(key, value) }
            if (lastReportedAt > 0L) putLong(KEY_LAST_REPORTED_AT, lastReportedAt)
        }
    }
}

internal fun Call.executeCounted(source: RaRequestSource): Response {
    val response = try {
        execute()
    } catch (e: IOException) {
        UsageStats.recordRaRequest(source, statusCode = null)
        throw e
    }
    UsageStats.recordRaRequest(source, response.code)
    return response
}
