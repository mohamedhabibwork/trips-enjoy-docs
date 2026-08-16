package com.trips_enjoy.ledger.application

import com.trips_enjoy.ledger.domain.InboxEventRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * Per ERD §10: inbox rows are kept for 30 days, then deleted. Mirrors the
 * audit-service / identity-service retention pattern.
 */
@Component
class InboxCleanup(private val inbox: InboxEventRepository) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${ledger-service.inbox.cron:0 30 5 * * *}")
    @Transactional
    fun cleanup() {
        val cutoff = Instant.now().minus(Duration.ofDays(30))
        val deleted = inbox.deleteAllByReceivedAtBefore(cutoff)
        if (deleted > 0) log.info("Deleted {} expired inbox rows", deleted)
    }
}
