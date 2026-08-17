package com.trips_enjoy.platform.messaging

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Configuration for the canonical inbox cleanup job.
 *
 * Properties (application.yml `platform.inbox.*`):
 * - [purgeCron]       - cron schedule (default hourly)
 * - [retentionDays]   - how long processed rows are kept (default 7)
 * - [enabled]         - kill switch (default true)
 */
@ConfigurationProperties("platform.inbox")
data class InboxCleanupPropertiesCanonical(
    val purgeCron: String = "0 0 * * * *",
    val retentionDays: Long = 7L,
    val enabled: Boolean = true,
)

/**
 * Spring Data repository for the canonical inbox cleanup job.
 *
 * Deletes rows whose `processed_at` is older than the retention
 * window — i.e. successfully-handled rows past the 7-day default.
 * Unprocessed (in-flight) rows are preserved.
 */
interface InboxRepositoryCanonicalCleanup : JpaRepository<InboxEventCanonical, UUID> {
    @Modifying
    @Query(
        """
        DELETE FROM InboxEventCanonical i
         WHERE i.processedAt IS NOT NULL
           AND i.processedAt < :cutoff
        """
    )
    fun deleteProcessedBefore(@Param("cutoff") cutoff: Instant): Int
}

/**
 * Hourly scheduled cleanup for canonical inbox rows.
 *
 * Per Phase B §B, deletes rows where
 * `processed_at < now() - INTERVAL '7 days'`. Unprocessed rows are
 * kept so they can be retried by the listener.
 */
@Component
open class InboxCleanupJobCanonical(
    private val repository: InboxRepositoryCanonicalCleanup,
    private val properties: InboxCleanupPropertiesCanonical,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${platform.inbox.purge-cron:0 0 * * * *}")
    @Transactional
    open fun cleanup() {
        if (!properties.enabled) return
        val cutoff = Instant.now().minus(Duration.ofDays(properties.retentionDays))
        val deleted = repository.deleteProcessedBefore(cutoff)
        if (deleted > 0) {
            log.info("deleted $deleted processed canonical inbox events older than {} days", properties.retentionDays)
        }
    }
}