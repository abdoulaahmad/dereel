package app.dereel.data

import app.dereel.data.db.DereelDatabase
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DATA-01 guard. Every exported schema version needs a migration path to the current version,
 * and the current version must have an exported schema. Bumping the version without a migration
 * fails here before it can silently lose settings or history.
 */
class SchemaGuardTest {
    private val schemaDir = File("schemas/app.dereel.data.db.DereelDatabase")

    @Test
    fun currentVersionHasAnExportedSchema() {
        val versions = exportedVersions()
        assertTrue("no exported schemas in ${schemaDir.absolutePath}", versions.isNotEmpty())
        assertEquals(DereelDatabase.VERSION, versions.last())
    }

    @Test
    fun everyOlderVersionMigratesStepByStep() {
        val versions = exportedVersions()
        for (version in versions.first() until DereelDatabase.VERSION) {
            assertTrue(
                "missing Migration($version, ${version + 1})",
                DereelDatabase.MIGRATIONS.any { it.startVersion == version && it.endVersion == version + 1 },
            )
        }
    }

    private fun exportedVersions(): List<Int> =
        schemaDir.listFiles().orEmpty().mapNotNull { it.nameWithoutExtension.toIntOrNull() }.sorted()
}
