package com.trips_enjoy.identity.application

import com.trips_enjoy.identity.domain.IdempotencyRecordRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Daily cleanup of expired idempotency rows per SRS §15 ("The service stores
 * `(actor, idempotency_key, request_hash, response_status, response_body,
 * expires_at)` for 24 h.").
 */
@Component
class IdempotencyCleanupJob(private val repo: IdempotencyRecordRepository) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    fun cleanup() {
        val deleted = repo.deleteAllByExpiresAtBefore(Instant.now())
        if (deleted > 0) log.info("Deleted {} expired idempotency rows", deleted)
    }
}
