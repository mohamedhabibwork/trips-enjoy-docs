package com.trips_enjoy.platform.data

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@ConfigurationProperties("platform.idempotency")
data class IdempotencyProperties(
    val cleanupCron: String = "0 0 3 * * *",
    val enabled: Boolean = true,
)

interface IdempotencyRepository : JpaRepository<IdempotencyRecord, UUID> {
    @Modifying
    @Query("DELETE FROM IdempotencyRecord r WHERE r.expiresAt < :cutoff")
    fun deleteExpired(cutoff: Instant): Int
}

@Component
open class IdempotencyCleanupJob(
    private val repository: IdempotencyRepository,
    private val properties: IdempotencyProperties,
) {
    @Scheduled(cron = "\${platform.idempotency.cleanup-cron:0 0 3 * * *}")
    @Transactional
    open fun cleanup() {
        if (!properties.enabled) return
        val deleted = repository.deleteExpired(Instant.now())
        if (deleted > 0) {
            org.slf4j.LoggerFactory.getLogger(javaClass).info("Deleted $deleted expired idempotency records")
        }
    }
}

@Configuration
@EnableConfigurationProperties(IdempotencyProperties::class)
internal class IdempotencyAutoConfiguration
