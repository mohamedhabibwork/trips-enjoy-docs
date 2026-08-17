package com.trips_enjoy.platform.data

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Configuration for the canonical idempotency cleanup job.
 *
 * Properties (application.yml `platform.idempotency.*`):
 * - [purgeCron]  - cron schedule (default `0 0 3 * * *` = 03:00 daily)
 * - [enabled]    - kill switch (default true)
 */
@ConfigurationProperties("platform.idempotency")
data class IdempotencyCleanupPropertiesCanonical(
    val purgeCron: String = "0 0 3 * * *",
    val enabled: Boolean = true,
)

/**
 * Spring Data repository for the canonical idempotency cleanup job.
 *
 * The `@Modifying` bulk delete returns the row count so the job can
 * log a summary.
 */
interface IdempotencyRepositoryCanonicalCleanup : JpaRepository<IdempotencyRecordCanonical, UUID> {
    @Modifying
    @Query("DELETE FROM IdempotencyRecordCanonical r WHERE r.expiresAt < :cutoff")
    fun deleteExpired(@Param("cutoff") cutoff: Instant): Int
}

/**
 * Daily scheduled cleanup for canonical idempotency records per
 * ADR-0027.
 *
 * Deletes rows where `expires_at < now()`. Default retention is 24h
 * via [PlatformIdempotencyProperties.ttlSeconds]; the schedule itself
 * runs at 03:00 daily. Services that need different retention set
 * `platform.idempotency.ttl-seconds` in their `application.yml`.
 */
@Component
open class IdempotencyCleanupJobCanonical(
    private val repository: IdempotencyRepositoryCanonicalCleanup,
    private val properties: IdempotencyCleanupPropertiesCanonical,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${platform.idempotency.purge-cron:0 0 3 * * *}")
    @Transactional
    open fun cleanup() {
        if (!properties.enabled) return
        val deleted = repository.deleteExpired(Instant.now())
        if (deleted > 0) {
            log.info("deleted $deleted expired canonical idempotency records")
        }
    }
}