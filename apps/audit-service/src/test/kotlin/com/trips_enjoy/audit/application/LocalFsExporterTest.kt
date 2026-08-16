package com.trips_enjoy.audit.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

class LocalFsExporterTest {

    @Test
    fun `export writes a file under the local dir and returns the canonical s3 uri`(@TempDir tempDir: Path) {
        val exporter = LocalFsExporter(
            baseDir = tempDir.toString(),
            s3Bucket = "trips-enjoy-platform-audit",
            s3Template = "s3://trips-enjoy-platform-audit/audit/exports/<yyyy>/<mm>/<dd>/",
        )
        val date = LocalDate.of(2026, 8, 1)
        val s3Path = exporter.export(date, "global", "{\"event_count\":42}")
        assertEquals(
            "s3://trips-enjoy-platform-audit/audit/exports/2026/08/01/global.json",
            s3Path,
        )
        val written = tempDir.resolve("2026/08/01/global.json")
        assertTrue(Files.exists(written))
        assertEquals("{\"event_count\":42}", Files.readString(written))
    }
}
