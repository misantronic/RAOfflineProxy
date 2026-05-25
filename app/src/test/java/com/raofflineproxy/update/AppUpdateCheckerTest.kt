package com.raofflineproxy.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdateCheckerTest {

    @Test
    fun selectLatestUpdate_returnsNull_forSameVersion() {
        val releases = listOfNotNull(releaseInfo("1.2.0-alpha1"))

        val result = AppUpdateChecker.selectLatestUpdate("1.2.0-alpha1", releases)

        assertNull(result)
    }

    @Test
    fun selectLatestUpdate_returnsAlphaUpgrade() {
        val releases = listOfNotNull(releaseInfo("1.2.0-alpha2"))

        val result = AppUpdateChecker.selectLatestUpdate("1.2.0-alpha1", releases)

        assertNotNull(result)
        assertEquals("1.2.0-alpha2", result?.versionName)
    }

    @Test
    fun selectLatestUpdate_ordersAlphaBeforeBetaBeforeStable() {
        val releases = listOfNotNull(
            releaseInfo("1.2.0-alpha2"),
            releaseInfo("1.2.0-beta1"),
            releaseInfo("1.2.0")
        )

        val result = AppUpdateChecker.selectLatestUpdate("1.2.0-alpha1", releases)

        assertNotNull(result)
        assertEquals("1.2.0", result?.versionName)
    }

    @Test
    fun selectLatestUpdate_returnsNull_whenCurrentStableIsNewest() {
        val releases = listOfNotNull(
            releaseInfo("1.2.0-alpha2"),
            releaseInfo("1.2.0-beta1")
        )

        val result = AppUpdateChecker.selectLatestUpdate("1.2.0", releases)

        assertNull(result)
    }

    @Test
    fun selectLatestUpdate_returnsNull_forInvalidCurrentVersion() {
        val releases = listOfNotNull(releaseInfo("1.2.0"))

        val result = AppUpdateChecker.selectLatestUpdate("not-a-version", releases)

        assertNull(result)
    }

    @Test
    fun selectLatestUpdate_choosesNewestAndroidCompatibleRelease() {
        val releases = listOfNotNull(
            releaseInfo("1.2.0-alpha2"),
            releaseInfo("1.2.0-beta1"),
            releaseInfo("1.2.0-alpha2")
        )

        val result = AppUpdateChecker.selectLatestUpdate("1.2.0-alpha1", releases)

        assertNotNull(result)
        assertEquals("1.2.0-beta1", result?.versionName)
    }

    @Test
    fun isUpdateNewerThanCurrent_returnsTrue_forNewerVersion() {
        val result = AppUpdateChecker.isUpdateNewerThanCurrent("1.2.0-alpha1", "1.2.0-alpha2")

        assertEquals(true, result)
    }

    @Test
    fun isUpdateNewerThanCurrent_returnsFalse_forSameOrOlderVersion() {
        assertEquals(false, AppUpdateChecker.isUpdateNewerThanCurrent("1.2.0-alpha1", "1.2.0-alpha1"))
        assertEquals(false, AppUpdateChecker.isUpdateNewerThanCurrent("1.2.0-alpha1", "1.0.0"))
    }
}
