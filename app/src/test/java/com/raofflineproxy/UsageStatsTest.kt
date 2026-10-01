package com.raofflineproxy

import com.raofflineproxy.diagnostics.DeviceInfo
import com.raofflineproxy.proxy.CACHE_BUDGET_WINDOW_MS
import com.raofflineproxy.proxy.DrainStop
import com.raofflineproxy.proxy.QueueDrainResult
import com.raofflineproxy.usage.RaRequestSource
import com.raofflineproxy.usage.UsageCounterKeys
import com.raofflineproxy.usage.UsageCounters
import com.raofflineproxy.usage.UsageGauges
import com.raofflineproxy.usage.ageBucket
import com.raofflineproxy.usage.buildUsagePayload
import com.raofflineproxy.usage.countBucket
import com.raofflineproxy.usage.isUsageReportDue
import com.raofflineproxy.usage.usageClientId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageStatsTest {

    private val start = 1_000_000L

    @Test
    fun requests_areCountedPerSource() {
        val counters = UsageCounters()
            .withRequest(RaRequestSource.Emulator, 200, start)
            .withRequest(RaRequestSource.Emulator, 200, start)
            .withRequest(RaRequestSource.Background, 200, start)

        assertEquals(2L, counters[UsageCounterKeys.requests(RaRequestSource.Emulator)])
        assertEquals(1L, counters[UsageCounterKeys.requests(RaRequestSource.Background)])
        assertEquals(0L, counters[UsageCounterKeys.requests(RaRequestSource.App)])
    }

    @Test
    fun failures_areClassifiedByStatus() {
        val counters = UsageCounters()
            .withRequest(RaRequestSource.App, null, start)
            .withRequest(RaRequestSource.App, 429, start)
            .withRequest(RaRequestSource.App, 503, start)
            .withRequest(RaRequestSource.App, 404, start)

        assertEquals(1L, counters[UsageCounterKeys.FAILURES_NETWORK])
        assertEquals(1L, counters[UsageCounterKeys.RATE_LIMITED])
        assertEquals(1L, counters[UsageCounterKeys.FAILURES_SERVER])
    }

    @Test
    fun maxRequestsPerWindow_keepsBusiestWindow() {
        var counters = UsageCounters()
        repeat(5) { counters = counters.withRequest(RaRequestSource.Background, 200, start) }
        val nextWindow = start + CACHE_BUDGET_WINDOW_MS
        repeat(2) { counters = counters.withRequest(RaRequestSource.Background, 200, nextWindow) }

        assertEquals(5L, counters[UsageCounterKeys.MAX_REQUESTS_PER_WINDOW])
        assertEquals(2L, counters[UsageCounterKeys.WINDOW_REQUESTS])
    }

    @Test
    fun maxRequestsPerWindow_restartsWhenClockJumpsBack() {
        var counters = UsageCounters()
        repeat(3) { counters = counters.withRequest(RaRequestSource.App, 200, start) }
        counters = counters.withRequest(RaRequestSource.App, 200, start - 1)

        assertEquals(1L, counters[UsageCounterKeys.WINDOW_REQUESTS])
        assertEquals(3L, counters[UsageCounterKeys.MAX_REQUESTS_PER_WINDOW])
    }

    @Test
    fun batch_withoutRequestsIsIgnored() {
        val counters = UsageCounters().withBatch(QueueDrainResult(0, 0, DrainStop.Paused), 1_000L)
        assertTrue(counters.values.isEmpty())
    }

    @Test
    fun batch_recordsOutcome() {
        val counters = UsageCounters()
            .withBatch(QueueDrainResult(40, 3, DrainStop.BudgetExhausted, timeLimited = true), 600_000L)
            .withBatch(QueueDrainResult(5, 0, DrainStop.Empty), 30_000L)
            .withBatch(QueueDrainResult(0, 0, DrainStop.Failed), 2_000L)

        assertEquals(3L, counters[UsageCounterKeys.BATCHES])
        assertEquals(1L, counters[UsageCounterKeys.BATCHES_TIME_LIMITED])
        assertEquals(632_000L, counters[UsageCounterKeys.BATCH_MS_TOTAL])
        assertEquals(45L, counters[UsageCounterKeys.QUEUE_CACHED])
        assertEquals(3L, counters[UsageCounterKeys.QUEUE_NO_MATCH])
        assertEquals(1L, counters[UsageCounterKeys.QUEUE_EMPTIED])
        assertEquals(1L, counters[UsageCounterKeys.QUEUE_FAILED])
    }

    @Test
    fun reportable_hidesWindowBookkeeping() {
        val reportable = UsageCounters().withRequest(RaRequestSource.App, 200, start).reportable()

        assertFalse(UsageCounterKeys.WINDOW_START in reportable)
        assertFalse(UsageCounterKeys.WINDOW_REQUESTS in reportable)
        assertEquals(1L, reportable[UsageCounterKeys.requests(RaRequestSource.App)])
    }

    @Test
    fun afterReport_keepsWhatArrivedDuringTheReport() {
        val beforeReport = UsageCounters()
            .withRequest(RaRequestSource.App, 200, start)
            .withRequest(RaRequestSource.App, 200, start)
        val sent = beforeReport.reportable()
        val afterReport = beforeReport
            .withRequest(RaRequestSource.App, 200, start)
            .afterReport(sent)

        assertEquals(1L, afterReport[UsageCounterKeys.requests(RaRequestSource.App)])
        assertEquals(3L, afterReport[UsageCounterKeys.MAX_REQUESTS_PER_WINDOW])
        assertEquals(3L, afterReport[UsageCounterKeys.WINDOW_REQUESTS])
    }

    @Test
    fun buckets() {
        assertEquals("0", countBucket(0))
        assertEquals("1-9", countBucket(9))
        assertEquals("100-249", countBucket(100))
        assertEquals("2500+", countBucket(10_000))
        assertEquals("none", ageBucket(null))
        assertEquals("<1h", ageBucket(59 * 60_000L))
        assertEquals("7d+", ageBucket(8L * 24 * 3_600_000))
    }

    @Test
    fun report_isDueOncePerUtcDay() {
        val day = 24L * 60 * 60 * 1000
        val todayStart = 20_000L * day
        val lateToday = todayStart + day - 1

        assertTrue(isUsageReportDue(0L, todayStart))
        assertFalse(isUsageReportDue(todayStart, lateToday))
        assertTrue(isUsageReportDue(lateToday, lateToday + 1))
        assertTrue(isUsageReportDue(todayStart, todayStart - 1))
    }

    @Test
    fun clientId_ignoresCaseAndWhitespaceButNotTheName() {
        assertEquals(usageClientId("Scott"), usageClientId(" scott "))
        assertNotEquals(usageClientId("scott"), usageClientId("scott2"))
        assertEquals(64, usageClientId("scott").length)
    }

    @Test
    fun payload_containsNoUsername() {
        val payload = buildUsagePayload(
            clientId = usageClientId("Scott"),
            info = DeviceInfo("AYN Thor", "15", "1.13.0", listOf("RetroArch")),
            buildType = "release",
            consentVersion = 1,
            gauges = UsageGauges(cachedGames = 120, queuedGames = 0, oldestQueuedAgeMs = null, pendingAwards = 2),
            counters = mapOf("requests_emulator" to 7L)
        )

        assertFalse(payload.toString().contains("Scott", ignoreCase = true))
        assertEquals("android", payload.getString("platform"))
        assertEquals("AYN Thor", payload.getString("device"))
        assertEquals("100-249", payload.getJSONObject("gauges").getString("cached_games"))
        assertEquals(7L, payload.getJSONObject("counters").getLong("requests_emulator"))
        assertEquals(1, payload.getInt("schema_version"))
        assertEquals(1, payload.getInt("consent_version"))
    }

    @Test
    fun consent_grantedForAnOlderScopeIsAskedAgain() {
        assertEquals(null, PrefsConstants.resolveUsageStatsConsent(granted = null, grantedVersion = 0, currentVersion = 2))
        assertEquals(true, PrefsConstants.resolveUsageStatsConsent(granted = true, grantedVersion = 2, currentVersion = 2))
        assertEquals(null, PrefsConstants.resolveUsageStatsConsent(granted = true, grantedVersion = 1, currentVersion = 2))
        assertEquals(false, PrefsConstants.resolveUsageStatsConsent(granted = false, grantedVersion = 1, currentVersion = 2))
    }
}
