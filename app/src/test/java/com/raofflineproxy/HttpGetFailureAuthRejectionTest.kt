package com.raofflineproxy

import com.raofflineproxy.proxy.HttpGetResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpGetFailureAuthRejectionTest {
    @Test
    fun http401_isAuthRejection() {
        assertTrue(HttpGetResult.Failure(kind = "http", statusCode = 401).isAuthRejection)
    }

    @Test
    fun otherHttpStatuses_areNotAuthRejection() {
        assertFalse(HttpGetResult.Failure(kind = "http", statusCode = 500).isAuthRejection)
        assertFalse(HttpGetResult.Failure(kind = "http", statusCode = 429).isAuthRejection)
    }

    @Test
    fun networkFailure_isNotAuthRejection() {
        assertFalse(HttpGetResult.Failure(kind = "network", exceptionMessage = "timeout").isAuthRejection)
    }
}
