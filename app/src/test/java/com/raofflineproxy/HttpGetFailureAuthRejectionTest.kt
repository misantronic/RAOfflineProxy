package com.raofflineproxy

import com.raofflineproxy.proxy.HttpGetResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpGetFailureAuthRejectionTest {
    private val invalidCredentialsBody =
        """{"Success":false,"Status":401,"Code":"invalid_credentials","Error":"Invalid user/token combination."}"""

    @Test
    fun http401WithInvalidCredentialsCode_isAuthRejection() {
        assertTrue(
            HttpGetResult.Failure(kind = "http", statusCode = 401, bodySnippet = invalidCredentialsBody).isAuthRejection
        )
    }

    @Test
    fun http401WithOtherCode_isNotAuthRejection() {
        assertFalse(
            HttpGetResult.Failure(kind = "http", statusCode = 401, bodySnippet = """{"Code":"access_denied"}""").isAuthRejection
        )
    }

    @Test
    fun http401WithoutBody_isNotAuthRejection() {
        assertFalse(HttpGetResult.Failure(kind = "http", statusCode = 401).isAuthRejection)
    }

    @Test
    fun otherHttpStatuses_areNotAuthRejection() {
        assertFalse(HttpGetResult.Failure(kind = "http", statusCode = 500, bodySnippet = invalidCredentialsBody).isAuthRejection)
        assertFalse(HttpGetResult.Failure(kind = "http", statusCode = 429).isAuthRejection)
    }

    @Test
    fun networkFailure_isNotAuthRejection() {
        assertFalse(HttpGetResult.Failure(kind = "network", exceptionMessage = "timeout").isAuthRejection)
    }
}
