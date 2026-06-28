package com.raofflineproxy

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppDatabaseSchemaLockTest {

    private val sourceVersion: Int by lazy {
        val source = File("src/main/java/com/raofflineproxy/data/AppDatabase.kt").readText()
        val match = Regex("""version\s*=\s*(\d+)""").find(source)
            ?: error("Could not find `version = N` in AppDatabase.kt")
        match.groupValues[1].toInt()
    }

    private val schemaDir = File("schemas/com.raofflineproxy.data.AppDatabase")

    @Test
    fun `exported schema exists for current database version`() {
        val schema = File(schemaDir, "$sourceVersion.json")
        assertTrue(
            "Missing exported schema ${schema.path} for @Database(version = $sourceVersion). " +
                "Run :app:kspDebugKotlin and commit the generated file.",
            schema.isFile
        )
        val version = JSONObject(schema.readText()).getJSONObject("database").getInt("version")
        assertEquals(sourceVersion, version)
    }

    @Test
    fun `database version is the highest committed schema`() {
        val highest = schemaDir.listFiles { f -> f.extension == "json" }
            .orEmpty()
            .mapNotNull { it.nameWithoutExtension.toIntOrNull() }
            .maxOrNull()
            ?: error("No committed schemas in ${schemaDir.path}")
        assertEquals(
            "@Database version ($sourceVersion) must be the newest committed schema ($highest). " +
                "A schema change without a version bump means an unintended cache wipe or runtime crash.",
            highest,
            sourceVersion
        )
    }
}
