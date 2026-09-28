package com.raofflineproxy.proxy

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal const val RATE_LIMIT_PAUSE_MS = 10L * 60 * 1000

/** Shared 429 state for the requests the app sends on its own: the cache queue and the periodic
 *  refresh. Any 429 from RetroAchievements pauses both for at least [RATE_LIMIT_PAUSE_MS]. */
internal object RateLimitBackoff {
    private val pausedUntil = AtomicLong()
    private val backgroundRuns = AtomicInteger()

    /** True while background work sends requests: a 429 then stops it instead of being retried. */
    val inBackground: Boolean get() = backgroundRuns.get() > 0

    inline fun <T> background(block: () -> T): T {
        enterBackground()
        try {
            return block()
        } finally {
            leaveBackground()
        }
    }

    fun enterBackground() {
        backgroundRuns.incrementAndGet()
    }

    fun leaveBackground() {
        backgroundRuns.decrementAndGet()
    }

    /** Pauses background work for at least ten minutes, longer if the server's Retry-After asks. */
    fun onRateLimited(retryAfterMs: Long?, now: Long = System.currentTimeMillis()) {
        val until = now + maxOf(RATE_LIMIT_PAUSE_MS, retryAfterMs ?: 0L)
        pausedUntil.accumulateAndGet(until, ::maxOf)
    }

    fun pausedUntil(now: Long = System.currentTimeMillis()): Long? =
        pausedUntil.get().takeIf { it > now }
}
