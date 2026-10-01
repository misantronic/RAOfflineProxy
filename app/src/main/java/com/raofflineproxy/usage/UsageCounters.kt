package com.raofflineproxy.usage

import com.raofflineproxy.proxy.CACHE_BUDGET_WINDOW_MS
import com.raofflineproxy.proxy.DrainStop
import com.raofflineproxy.proxy.QueueDrainResult

internal enum class RaRequestSource(val key: String) {
    Emulator("emulator"),
    AwardSync("award_sync"),
    Background("background"),
    App("app")
}

internal object UsageCounterKeys {
    const val WINDOW_START = "window_start"
    const val WINDOW_REQUESTS = "window_requests"
    const val MAX_REQUESTS_PER_WINDOW = "max_requests_per_window"
    const val FAILURES_NETWORK = "failures_network"
    const val FAILURES_SERVER = "failures_server"
    const val RATE_LIMITED = "rate_limited"
    const val BATCHES = "batches"
    const val BATCHES_TIME_LIMITED = "batches_time_limited"
    const val BATCH_MS_TOTAL = "batch_ms_total"
    const val QUEUE_CACHED = "queue_cached"
    const val QUEUE_NO_MATCH = "queue_no_match"
    const val QUEUE_EMPTIED = "queue_emptied"
    const val QUEUE_FAILED = "queue_failed"

    val INTERNAL = setOf(WINDOW_START, WINDOW_REQUESTS)

    fun requests(source: RaRequestSource): String = "requests_${source.key}"
}

private const val HTTP_TOO_MANY_REQUESTS = 429

/** Counters since the last successful report. [MAX_REQUESTS_PER_WINDOW][UsageCounterKeys.MAX_REQUESTS_PER_WINDOW]
 *  tracks the busiest fixed 30-minute window, the same window length the caching budget uses. */
internal data class UsageCounters(val values: Map<String, Long> = emptyMap()) {

    operator fun get(key: String): Long = values[key] ?: 0L

    fun withRequest(
        source: RaRequestSource,
        statusCode: Int?,
        now: Long,
        windowMs: Long = CACHE_BUDGET_WINDOW_MS
    ): UsageCounters {
        val next = values.toMutableMap()
        next.increment(UsageCounterKeys.requests(source))
        val windowStart = values[UsageCounterKeys.WINDOW_START]
        val windowRequests = if (windowStart == null || now < windowStart || now - windowStart >= windowMs) {
            next[UsageCounterKeys.WINDOW_START] = now
            1L
        } else {
            this[UsageCounterKeys.WINDOW_REQUESTS] + 1
        }
        next[UsageCounterKeys.WINDOW_REQUESTS] = windowRequests
        next[UsageCounterKeys.MAX_REQUESTS_PER_WINDOW] = maxOf(this[UsageCounterKeys.MAX_REQUESTS_PER_WINDOW], windowRequests)
        when {
            statusCode == null -> next.increment(UsageCounterKeys.FAILURES_NETWORK)
            statusCode == HTTP_TOO_MANY_REQUESTS -> next.increment(UsageCounterKeys.RATE_LIMITED)
            statusCode >= 500 -> next.increment(UsageCounterKeys.FAILURES_SERVER)
        }
        return UsageCounters(next)
    }

    fun withBatch(result: QueueDrainResult, durationMs: Long): UsageCounters {
        val sentRequests = result.processed > 0 || result.timeLimited ||
            result.stop == DrainStop.Failed || result.stop == DrainStop.RateLimited
        if (!sentRequests) return this
        val next = values.toMutableMap()
        next.increment(UsageCounterKeys.BATCHES)
        next.increment(UsageCounterKeys.BATCH_MS_TOTAL, durationMs.coerceAtLeast(0L))
        next.increment(UsageCounterKeys.QUEUE_CACHED, result.cached.toLong())
        next.increment(UsageCounterKeys.QUEUE_NO_MATCH, result.noMatch.toLong())
        if (result.timeLimited) next.increment(UsageCounterKeys.BATCHES_TIME_LIMITED)
        if (result.stop == DrainStop.Failed) next.increment(UsageCounterKeys.QUEUE_FAILED)
        if (result.stop == DrainStop.Empty) next.increment(UsageCounterKeys.QUEUE_EMPTIED)
        return UsageCounters(next)
    }

    fun reportable(): Map<String, Long> =
        values.filterKeys { it !in UsageCounterKeys.INTERNAL }.filterValues { it > 0 }

    /** Removes what [reported] sent and keeps whatever was counted while the report was in flight. */
    fun afterReport(reported: Map<String, Long>): UsageCounters {
        val next = values.toMutableMap()
        reported.forEach { (key, sent) ->
            next[key] = if (key == UsageCounterKeys.MAX_REQUESTS_PER_WINDOW) {
                this[UsageCounterKeys.WINDOW_REQUESTS]
            } else {
                (this[key] - sent).coerceAtLeast(0L)
            }
        }
        return UsageCounters(next.filterValues { it > 0 })
    }

    private fun MutableMap<String, Long>.increment(key: String, by: Long = 1L) {
        if (by != 0L) this[key] = (this[key] ?: 0L) + by
    }
}

internal fun countBucket(count: Int): String = when {
    count <= 0 -> "0"
    count < 10 -> "1-9"
    count < 50 -> "10-49"
    count < 100 -> "50-99"
    count < 250 -> "100-249"
    count < 500 -> "250-499"
    count < 1000 -> "500-999"
    count < 2500 -> "1000-2499"
    else -> "2500+"
}

internal fun ageBucket(ageMs: Long?): String {
    val hours = (ageMs ?: return "none") / 3_600_000L
    return when {
        hours < 1 -> "<1h"
        hours < 6 -> "1-6h"
        hours < 24 -> "6-24h"
        hours < 24 * 7 -> "1-7d"
        else -> "7d+"
    }
}
