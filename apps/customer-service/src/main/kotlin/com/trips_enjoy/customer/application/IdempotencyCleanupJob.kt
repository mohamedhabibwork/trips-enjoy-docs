package com.trips_enjoy.customer.application

import com.trips_enjoy.customer.domain.IdempotencyRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Purges expired idempotency rows older than the configured TTL
 * (default 24h, per SRS §15).
 */
@Component
class IdempotencyCleanupJob(
    private val idempotencyRepository: IdempotencyRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${customer-service.idempotency.purge-interval-ms:3600000}")
    @Transactional
    fun purgeExpired() {
        val purged = idempotencyRepository.deleteAllByExpiresAtBefore(Instant.now())
        if (purged > 0) {
            log.info("Purged {} expired customer idempotency rows", purged)
        }
    }
}
