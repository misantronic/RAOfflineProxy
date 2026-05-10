package com.raofflineproxy

import com.raofflineproxy.ui.ImportedCredentials
import com.raofflineproxy.ui.selectImportedCredentials
import org.junit.Assert.assertEquals
import org.junit.Test

class ImportedCredentialsSelectionTest {
    @Test
    fun selectImportedCredentials_prefersRetroArchToken() {
        val retroArch = ImportedCredentials.Token("retro", "retro-token")
        val dolphin = ImportedCredentials.Token("dolphin", "dolphin-token")

        assertEquals(retroArch, selectImportedCredentials(retroArch, dolphin))
    }

    @Test
    fun selectImportedCredentials_prefersDolphinTokenOverRetroArchPassword() {
        val retroArch = ImportedCredentials.Password("retro", "password")
        val dolphin = ImportedCredentials.Token("dolphin", "dolphin-token")

        assertEquals(dolphin, selectImportedCredentials(retroArch, dolphin))
    }

    @Test
    fun selectImportedCredentials_usesRetroArchPasswordAsLastFallback() {
        val retroArch = ImportedCredentials.Password("retro", "password")

        assertEquals(retroArch, selectImportedCredentials(retroArch, null))
    }
}
