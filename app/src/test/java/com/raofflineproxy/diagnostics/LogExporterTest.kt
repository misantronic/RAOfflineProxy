package com.raofflineproxy.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogExporterTest {

    @Test
    fun stripMetadata_keepsRAProxyTag() {
        val line = "07-21 10:15:23.123 D/RAProxy/Hash( 1234): computed hash abc123"

        assertEquals(
            "07-21 10:15:23.123 RAProxy/Hash: computed hash abc123",
            LogExporter.stripMetadata(line)
        )
    }

    @Test
    fun stripMetadata_keepsBareRAProxyTag() {
        val line = "07-21 10:15:23.123 I/RAProxy( 1234): proxy started on port 8080"

        assertEquals(
            "07-21 10:15:23.123 RAProxy: proxy started on port 8080",
            LogExporter.stripMetadata(line)
        )
    }

    @Test
    fun stripMetadata_dropsOtherTags() {
        assertNull(LogExporter.stripMetadata("07-21 10:15:23.123 D/ProxyService( 1234): onStartCommand"))
    }

    @Test
    fun stripMetadata_dropsSystemNoise() {
        assertNull(LogExporter.stripMetadata("07-21 10:15:23.123 I/ActivityManager(  555): Start proc 1234"))
    }

    @Test
    fun stripMetadata_dropsMalformedLine() {
        assertNull(LogExporter.stripMetadata("not a logcat line at all"))
    }
}
