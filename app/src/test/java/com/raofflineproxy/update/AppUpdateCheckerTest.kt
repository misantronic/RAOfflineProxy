package com.raofflineproxy.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdateCheckerTest {

    @Test
    fun selectLatestUpdate_returnsNull_forSameVersion() {
        val releases = listOfNotNull(releaseInfo("1.3.0-alpha1"))

        val result = AppUpdateChecker.selectLatestUpdate("1.3.0-alpha1", releases)

        assertNull(result)
    }

    @Test
    fun selectLatestUpdate_returnsAlphaUpgrade() {
        val releases = listOfNotNull(releaseInfo("1.3.0-alpha2"))

        val result = AppUpdateChecker.selectLatestUpdate("1.3.0-alpha1", releases)

        assertNotNull(result)
        assertEquals("1.3.0-alpha2", result?.versionName)
    }

    @Test
    fun selectLatestUpdate_ordersAlphaBeforeBetaBeforeStable() {
        val releases = listOfNotNull(
            releaseInfo("1.3.0-alpha2"),
            releaseInfo("1.3.0-beta1"),
            releaseInfo("1.3.0")
        )

        val result = AppUpdateChecker.selectLatestUpdate("1.3.0-alpha1", releases)

        assertNotNull(result)
        assertEquals("1.3.0", result?.versionName)
    }

    @Test
    fun selectLatestUpdate_returnsNull_whenCurrentStableIsNewest() {
        val releases = listOfNotNull(
            releaseInfo("1.3.0-alpha2"),
            releaseInfo("1.3.0-beta1")
        )

        val result = AppUpdateChecker.selectLatestUpdate("1.3.0", releases)

        assertNull(result)
    }

    @Test
    fun selectLatestUpdate_returnsNull_forInvalidCurrentVersion() {
        val releases = listOfNotNull(releaseInfo("1.3.0"))

        val result = AppUpdateChecker.selectLatestUpdate("not-a-version", releases)

        assertNull(result)
    }

    @Test
    fun selectLatestUpdate_choosesNewestAndroidCompatibleRelease() {
        val releases = listOfNotNull(
            releaseInfo("1.3.0-alpha2"),
            releaseInfo("1.3.0-beta1"),
            releaseInfo("1.3.0-alpha2")
        )

        val result = AppUpdateChecker.selectLatestUpdate("1.3.0-alpha1", releases)

        assertNotNull(result)
        assertEquals("1.3.0-beta1", result?.versionName)
    }

    @Test
    fun isUpdateNewerThanCurrent_returnsTrue_forNewerVersion() {
        val result = AppUpdateChecker.isUpdateNewerThanCurrent("1.3.0-alpha1", "1.3.0-alpha2")

        assertEquals(true, result)
    }

    @Test
    fun isUpdateNewerThanCurrent_returnsFalse_forSameOrOlderVersion() {
        assertEquals(false, AppUpdateChecker.isUpdateNewerThanCurrent("1.3.0-alpha1", "1.3.0-alpha1"))
        assertEquals(false, AppUpdateChecker.isUpdateNewerThanCurrent("1.3.0-alpha1", "1.0.0"))
    }

    @Test
    fun selectLatestUpdate_ignoresNightly_forStableInstall() {
        val releases = listOfNotNull(releaseInfo("1.3.0-alpha1-nightly.57"))

        val result = AppUpdateChecker.selectLatestUpdate("1.3.0-alpha1", releases)

        assertNull(result)
    }

    @Test
    fun selectLatestUpdate_returnsNewerNightly_forNightlyInstall() {
        val releases = listOfNotNull(
            releaseInfo("1.3.0-alpha1"),
            releaseInfo("1.3.0-alpha1-nightly.58")
        )

        val result = AppUpdateChecker.selectLatestUpdate("1.3.0-alpha1-nightly.57", releases)

        assertEquals("1.3.0-alpha1-nightly.58", result?.versionName)
    }

    @Test
    fun selectLatestUpdate_ignoresStableOfSameBase_forNightlyInstall() {
        val releases = listOfNotNull(releaseInfo("1.3.0-alpha1"))

        val result = AppUpdateChecker.selectLatestUpdate("1.3.0-alpha1-nightly.57", releases)

        assertNull(result)
    }

    @Test
    fun selectLatestUpdate_returnsNewerStable_forNightlyInstall() {
        val releases = listOfNotNull(
            releaseInfo("1.3.0-alpha1-nightly.57"),
            releaseInfo("1.3.0-alpha2")
        )

        val result = AppUpdateChecker.selectLatestUpdate("1.3.0-alpha1-nightly.57", releases)

        assertEquals("1.3.0-alpha2", result?.versionName)
    }

    @Test
    fun selectLatestUpdate_prefersNightlyOverStableOfSameBase() {
        val releases = listOfNotNull(
            releaseInfo("1.3.0-alpha2"),
            releaseInfo("1.3.0-alpha2-nightly.60")
        )

        val result = AppUpdateChecker.selectLatestUpdate("1.3.0-alpha1-nightly.57", releases)

        assertEquals("1.3.0-alpha2-nightly.60", result?.versionName)
    }

    @Test
    fun nightlyVersionName_readsVersionAfterPlatformLabel() {
        val release = JSONObject().put("name", "Android 1.3.0-alpha1-nightly.57")

        assertEquals("1.3.0-alpha1-nightly.57", AppUpdateChecker.nightlyVersionName(release))
    }

    @Test
    fun nightlyVersionName_acceptsBareVersionTitle() {
        val release = JSONObject().put("name", "1.3.0-alpha1-nightly.57")

        assertEquals("1.3.0-alpha1-nightly.57", AppUpdateChecker.nightlyVersionName(release))
    }

    @Test
    fun isUpdateNewerThanCurrent_rejectsMalformedNightly() {
        assertEquals(false, AppUpdateChecker.isUpdateNewerThanCurrent("1.3.0-alpha1", "1.3.0-alpha1-nightly."))
    }
}
