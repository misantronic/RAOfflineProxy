package com.raofflineproxy

import com.raofflineproxy.data.CacheKeys
import com.raofflineproxy.proxy.BudgetWindow
import com.raofflineproxy.proxy.CACHE_BUDGET_LIMIT
import com.raofflineproxy.proxy.CACHE_BUDGET_WINDOW_MS
import com.raofflineproxy.proxy.CACHE_LOOKUP_LIMIT
import com.raofflineproxy.proxy.CachedGameIdLookup
import com.raofflineproxy.proxy.QueuedRom
import com.raofflineproxy.proxy.classifyCachedGameId
import com.raofflineproxy.proxy.estimateQueue
import com.raofflineproxy.proxy.windowProgressTotal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CachingBudgetTest {

    private val start = 1_000_000L

    // ── Budget window ──

    @Test
    fun budget_grantsGamesUpToLimitThenDenies() {
        var window = BudgetWindow()
        repeat(CACHE_BUDGET_LIMIT) {
            val (granted, next) = window.tryAcquireGame(start)
            assertTrue(granted)
            window = next
        }
        assertFalse(window.tryAcquireGame(start + 1).first)
        assertFalse(window.tryAcquireLookup(start + 1).first)
    }

    @Test
    fun budget_lookupsDoNotUseGames() {
        var window = BudgetWindow()
        repeat(CACHE_BUDGET_LIMIT) {
            val (granted, next) = window.tryAcquireLookup(start)
            assertTrue(granted)
            window = next
        }
        assertEquals(CACHE_BUDGET_LIMIT, window.remaining(start))
        assertTrue(window.tryAcquireGame(start).first)
    }

    @Test
    fun budget_closesWhenLookupsRunOut() {
        val window = BudgetWindow(windowStart = start, used = 10, lookups = CACHE_LOOKUP_LIMIT)
        assertFalse(window.tryAcquireLookup(start + 1).first)
        assertEquals(0, window.remaining(start + 1))
        assertEquals(start + CACHE_BUDGET_WINDOW_MS, window.nextAvailableAt(start + 1))
    }

    @Test
    fun budget_resetsAfterWindow() {
        val full = BudgetWindow(windowStart = start, used = CACHE_BUDGET_LIMIT, lookups = CACHE_LOOKUP_LIMIT)
        assertFalse(full.tryAcquireGame(start + CACHE_BUDGET_WINDOW_MS - 1).first)
        val (granted, next) = full.tryAcquireLookup(start + CACHE_BUDGET_WINDOW_MS)
        assertTrue(granted)
        assertEquals(BudgetWindow(windowStart = start + CACHE_BUDGET_WINDOW_MS, lookups = 1), next)
    }

    @Test
    fun budget_clockJumpingBackStartsFreshWindow() {
        val full = BudgetWindow(windowStart = start, used = CACHE_BUDGET_LIMIT)
        assertTrue(full.tryAcquireGame(start - 1).first)
    }

    @Test
    fun budget_remainingAndNextAvailable() {
        val window = BudgetWindow(windowStart = start, used = 40, lookups = 120)
        assertEquals(CACHE_BUDGET_LIMIT - 40, window.remaining(start + 10))
        assertEquals(start + 10, window.nextAvailableAt(start + 10))

        val full = BudgetWindow(windowStart = start, used = CACHE_BUDGET_LIMIT)
        assertEquals(0, full.remaining(start + 10))
        assertEquals(start + CACHE_BUDGET_WINDOW_MS, full.nextAvailableAt(start + 10))
    }

    @Test
    fun budget_jsonRoundTripAndGarbage() {
        val window = BudgetWindow(windowStart = start, used = 7, lookups = 21)
        assertEquals(window, BudgetWindow.fromJson(window.toJson()))
        assertEquals(BudgetWindow(start, 7, 0), BudgetWindow.fromJson("""{"windowStart":$start,"used":7}"""))
        assertEquals(BudgetWindow(), BudgetWindow.fromJson("not json"))
        assertEquals(BudgetWindow(), BudgetWindow.fromJson(null))
    }

    // ── Queue rows ──

    @Test
    fun queuedRom_jsonRoundTrip() {
        val rom = QueuedRom(listOf("abc", "def"), "/storage/roms/game.sfc", "game.sfc", start, attempts = 1)
        assertEquals(rom, QueuedRom.fromJson(rom.toJson()))
    }

    @Test
    fun queuedRom_keepsMissingPathAsNull() {
        val rom = QueuedRom(listOf("abc"), null, "game.sfc", start)
        assertNull(QueuedRom.fromJson(rom.toJson())?.sourceRomPath)
    }

    @Test
    fun queuedRom_rejectsInvalidRows() {
        assertNull(QueuedRom.fromJson("not json"))
        assertNull(QueuedRom.fromJson("""{"hashes":[],"label":"x"}"""))
        assertNull(QueuedRom.fromJson("""{"label":"x"}"""))
    }

    @Test
    fun queuedRom_isKeyedByFirstHash() {
        val rom = QueuedRom(listOf("ABC", "def"), null, "game", start)
        assertEquals(CacheKeys.cacheQueue("abc"), rom.key)
        assertEquals("cachequeue:abc", rom.key)
    }

    @Test
    fun queuedRom_isDroppedAfterThirdFailedAttempt() {
        val rom = QueuedRom(listOf("abc"), null, "game", start)
        val once = rom.afterFailedAttempt()
        val twice = once?.afterFailedAttempt()
        assertEquals(1, once?.attempts)
        assertEquals(2, twice?.attempts)
        assertNull(twice?.afterFailedAttempt())
    }

    // ── Estimate ──

    @Test
    fun estimate_withinBudgetNeedsNoConfirmation() {
        val estimate = estimateQueue(candidates = 80, alreadyKnown = 0, budgetRemaining = 100, queuedNow = 0)
        assertEquals(80, estimate.cachedNow)
        assertEquals(0, estimate.newlyQueued)
        assertFalse(estimate.needsConfirmation)
    }

    @Test
    fun estimate_exactlyHundredQueuedNeedsNoConfirmation() {
        val estimate = estimateQueue(candidates = 200, alreadyKnown = 0, budgetRemaining = 100, queuedNow = 0)
        assertEquals(100, estimate.queuedAfter)
        assertFalse(estimate.needsConfirmation)
    }

    @Test
    fun estimate_aboveHundredQueuedNeedsConfirmation() {
        val estimate = estimateQueue(candidates = 250, alreadyKnown = 0, budgetRemaining = 100, queuedNow = 0)
        assertEquals(100, estimate.cachedNow)
        assertEquals(150, estimate.newlyQueued)
        assertEquals(150, estimate.queuedAfter)
        assertEquals(60, estimate.etaMinutes)
        assertTrue(estimate.needsConfirmation)
    }

    @Test
    fun estimate_ignoresRomsAlreadyKnownByPath() {
        val estimate = estimateQueue(candidates = 300, alreadyKnown = 290, budgetRemaining = 100, queuedNow = 0)
        assertEquals(10, estimate.cachedNow)
        assertEquals(0, estimate.newlyQueued)
        assertFalse(estimate.needsConfirmation)
    }

    @Test
    fun estimate_countsWhatIsAlreadyWaiting() {
        val estimate = estimateQueue(candidates = 10, alreadyKnown = 0, budgetRemaining = 0, queuedNow = 95)
        assertEquals(10, estimate.newlyQueued)
        assertEquals(105, estimate.queuedAfter)
        assertTrue(estimate.needsConfirmation)
    }

    @Test
    fun estimate_addingNothingNeverAsksEvenWithBigQueue() {
        val estimate = estimateQueue(candidates = 5, alreadyKnown = 5, budgetRemaining = 0, queuedNow = 500)
        assertFalse(estimate.needsConfirmation)
    }

    @Test
    fun estimate_etaRoundsUpToWholeWindows() {
        assertEquals(30, estimateQueue(101, 0, 100, 0).etaMinutes)
        assertEquals(300, estimateQueue(1000, 0, 0, 0).etaMinutes)
    }

    // ── Caching progress within a budget window ──

    @Test
    fun windowProgress_boundedByQueue() {
        assertEquals(3, windowProgressTotal(cachedBefore = 0, queuedIncludingCurrent = 3, gamesLeft = 100))
    }

    @Test
    fun windowProgress_boundedByBudget() {
        assertEquals(100, windowProgressTotal(cachedBefore = 0, queuedIncludingCurrent = 250, gamesLeft = 100))
    }

    @Test
    fun windowProgress_staysStableWhileDraining() {
        assertEquals(100, windowProgressTotal(cachedBefore = 40, queuedIncludingCurrent = 210, gamesLeft = 60))
        assertEquals(100, windowProgressTotal(cachedBefore = 99, queuedIncludingCurrent = 151, gamesLeft = 1))
    }

    @Test
    fun windowProgress_shrinksAsUnknownRomsLeaveTheQueue() {
        assertEquals(12, windowProgressTotal(cachedBefore = 10, queuedIncludingCurrent = 2, gamesLeft = 50))
    }

    // ── Cached gameid classification ──

    @Test
    fun classify_missingRowNeedsLookup() {
        assertEquals(CachedGameIdLookup.Unknown, classifyCachedGameId(null, 0L, start))
    }

    @Test
    fun classify_matchNeverExpires() {
        assertEquals(
            CachedGameIdLookup.Match(10701),
            classifyCachedGameId("""{"Success":true,"GameID":10701}""", 0L, start)
        )
    }

    @Test
    fun classify_freshNoMatchIsAnswered() {
        assertEquals(
            CachedGameIdLookup.NoMatch,
            classifyCachedGameId("""{"Success":true,"GameID":0}""", start - 1_000, start)
        )
    }

    @Test
    fun classify_expiredNoMatchNeedsLookup() {
        val eightDays = 8L * 24 * 60 * 60 * 1000
        assertEquals(
            CachedGameIdLookup.Unknown,
            classifyCachedGameId("""{"Success":true,"GameID":0}""", start - eightDays, start)
        )
    }

    @Test
    fun classify_garbageNeedsLookup() {
        assertEquals(CachedGameIdLookup.Unknown, classifyCachedGameId("<html>", start, start))
    }
}
