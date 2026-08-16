package com.trips_enjoy.customer.application

import com.trips_enjoy.customer.domain.InboxRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Purges dedup rows older than 24h (INTEGRATION.md §5: inbox TTL 24h).
 * Mirrors the configuration-service/audit-service pattern.
 */
@Component
class InboxCleanupJob(
    private val inboxRepository: InboxRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${customer-service.inbox.purge-interval-ms:3600000}")
    @Transactional
    fun purgeExpired() {
        val cutoff = Instant.now().minusSeconds(86_400)
        val purged = inboxRepository.deleteAllByReceivedAtBefore(cutoff)
        if (purged > 0) {
            log.info("Purged {} expired customer inbox rows", purged)
        }
    }
}
