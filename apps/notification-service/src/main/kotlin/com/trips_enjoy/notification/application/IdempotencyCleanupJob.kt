package com.trips_enjoy.notification.application

import com.trips_enjoy.notification.domain.IdempotencyRecordRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Daily cleanup of expired idempotency rows per
 * docs/architecture/API_STANDARDS.md §9 (24h TTL).
 *
 * Mirrors identity-service's `IdempotencyCleanupJob`.
 */
@Component
class IdempotencyCleanupJob(private val records: IdempotencyRecordRepository) {
	private val log = LoggerFactory.getLogger(javaClass)

	@Scheduled(cron = "\${notification-service.idempotency.cleanup-cron:0 0 3 * * *}")
	fun cleanup() {
		val removed = records.deleteExpired(Instant.now())
		if (removed > 0) log.info("Removed {} expired idempotency records", removed)
	}
}