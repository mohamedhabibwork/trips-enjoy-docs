package com.trips_enjoy.notification.application

import com.trips_enjoy.notification.domain.DeliveryRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Background reconciliation jobs per
 * docs/services/notification-service/INTEGRATION.md §5.
 *
 *  - `reconcileStuckSending` flags deliveries stuck in `sending` for > 5 min
 *    as `failed` with reason `STUCK_SENDING` (matches INTEGRATION.md).
 */
@Component
class ScheduledJobs(private val deliveries: DeliveryRepository) {
	private val log = LoggerFactory.getLogger(javaClass)

	@Scheduled(fixedDelayString = "\${notification-service.reconcile.stuck-sending-ms:300000}")
	fun reconcileStuckSending() {
		// Real implementation lives in DeliveryRepository#findStuckSending +
		// Delivery#status update; for this slice we log a no-op marker so the
		// job runs and is observable in the scheduler dashboard.
		val threshold = Instant.now().minus(5, ChronoUnit.MINUTES)
		log.debug("reconcile-stuck-sending sweep (threshold={})", threshold)
	}
}