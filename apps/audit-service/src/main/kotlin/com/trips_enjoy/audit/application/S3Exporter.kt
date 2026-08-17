package com.trips_enjoy.audit.application

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Driver interface for the nightly audit export per INTEGRATION §2 / WORKFLOWS
 * §4. Two implementations are wired:
 *
 *   - `LocalFsExporter` (default) writes to the host filesystem — used in dev
 *     and CI where no S3 credentials are available.
 *   - The platform's S3 implementation is supplied via the shared
 *     `platform-spring-boot-starter` and is activated with
 *     `audit-service.export.driver=s3`.
 *
 * The contract is intentionally small: write a JSON blob to a tenant-scoped
 * path, return the canonical `s3://...` URI the caller can log.
 */
interface S3Exporter {
    /**
     * @param date the export date (yesterday in production; "today" for ad-hoc).
     * @param tenantId the tenant scope (e.g. `global`).
     * @param body the serialized audit JSON.
     * @return the URI where the blob was written.
     */
    fun export(date: LocalDate, tenantId: String, body: String): String
}

@Component
@ConditionalOnProperty(name = ["audit-service.export.driver"], havingValue = "local", matchIfMissing = true)
class LocalFsExporter(
    @Value("\${audit-service.export.local-fallback-dir:/tmp/audit-exports}") private val baseDir: String,
    @Value("\${audit-service.export.s3-bucket:trips-enjoy-platform-audit}") private val s3Bucket: String,
    @Value("\${audit-service.export.s3-path-template:s3://trips-enjoy-platform-audit/audit/exports/<yyyy>/<mm>/<dd>/}") private val s3Template: String,
) : S3Exporter {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun export(date: LocalDate, tenantId: String, body: String): String {
        val dir: Path = Paths.get(baseDir, date.year.toString(), "%02d".format(date.monthValue), "%02d".format(date.dayOfMonth))
        Files.createDirectories(dir)
        val file = dir.resolve("$tenantId.json")
        try {
            Files.writeString(file, body)
        } catch (exception: IOException) {
            log.error("Failed to write local export to {}: {}", file, exception.message)
            throw exception
        }
        // Return the canonical s3:// URI per docs/architecture/EVENT_ARCHITECTURE.md so
        // downstream consumers always see the same scheme regardless of where the
        // file physically lives in dev.
        val s3Uri = s3Template
            .replace("<yyyy>", date.year.toString())
            .replace("<mm>", "%02d".format(date.monthValue))
            .replace("<dd>", "%02d".format(date.dayOfMonth))
            .trimEnd('/') + "/$tenantId.json"
        log.info("Wrote audit export {} ({} bytes)", s3Uri, Files.size(file))
        return s3Uri
    }

    companion object {
        /** Date format used in the export path — exported for tests. */
        val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    }
}
