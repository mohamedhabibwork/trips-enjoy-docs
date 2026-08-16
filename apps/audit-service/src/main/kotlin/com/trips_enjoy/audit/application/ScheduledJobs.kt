package com.trips_enjoy.audit.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.audit.domain.OutboxEvent
import com.trips_enjoy.audit.domain.OutboxEventRepository
import com.trips_enjoy.audit.util.uuidV7
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Cron-driven operational tasks:
 *   - daily export (INTEGRATION §3.1, WORKFLOWS §4)
 *   - daily hash-chain verification (emits `audit.hash_chain.verified.v1`)
 *   - inbox cleanup (7-day retention per ERD §10)
 *
 * The 3 jobs are colocated to keep the operational surface small. Each is
 * independently transactional and emits an outbox event on success so the
 * downstream notification / dashboard tooling can subscribe.
 */
@Component
class ScheduledJobs(
    private val exportService: ExportService,
    private val verifyService: AuditVerifyService,
    private val inboxRepository: com.trips_enjoy.audit.domain.InboxEventRepository,
    private val outbox: OutboxEventRepository,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val nightlyVerifyTimer: Timer = Timer.builder("audit_hash_chain_verify_seconds")
        .description("Wall-clock seconds spent running the daily hash chain verification job")
        .publishPercentiles(0.5, 0.95)
        .register(meterRegistry)

    @Scheduled(cron = "\${audit-service.export.cron:0 0 4 * * *}")
    fun nightlyExport() {
        try {
            val yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1)
            val result = exportService.exportDay(yesterday, "global")
            log.info("Nightly export for {} complete: {} events, {}", yesterday, result.eventCount, result.s3Path)
        } catch (exception: Exception) {
            log.error("Nightly export failed: {}", exception.message, exception)
        }
    }

    @Scheduled(cron = "0 0 5 * * *")
    @Transactional
    fun dailyHashChainVerification() {
        val sample = Timer.start(meterRegistry)
        try {
            doDailyHashChainVerification()
        } finally {
            sample.stop(nightlyVerifyTimer)
        }
    }

    private fun doDailyHashChainVerification() {
        val chainLength = verifyService.verifyChainLength()
        val envelope = objectMapper.writeValueAsString(
            mapOf(
                "event_id" to uuidV7().toString(),
                "event_name" to "audit.hash_chain.verified.v1",
                "occurred_at" to Instant.now().toString(),
                "schema_version" to 1,
                "producer" to "audit-service",
                "tenant_id" to "global",
                "correlation_id" to uuidV7().toString(),
                "aggregate_type" to "HashChainVerification",
                "aggregate_id" to LocalDate.now(ZoneOffset.UTC).toString(),
                "data" to mapOf(
                    "verified" to true,
                    "chain_length" to chainLength,
                    "verified_at" to Instant.now().toString(),
                ),
            ),
        )
        outbox.save(
            OutboxEvent(
                id = uuidV7(),
                aggregateType = "HashChainVerification",
                aggregateId = null,
                topic = "audit.hash_chain.verified",
                eventName = "audit.hash_chain.verified.v1",
                payload = envelope,
            ),
        )
        log.info("Daily hash chain verification complete: {} rows", chainLength)
    }

    /** Per ERD §10: inbox rows are kept for 7 days. */
    @Scheduled(cron = "0 30 5 * * *")
    @Transactional
    fun inboxCleanup() {
        val cutoff = Instant.now().minus(Duration.ofDays(7))
        val deleted = inboxRepository.deleteAllByReceivedAtBefore(cutoff)
        if (deleted > 0) log.info("Deleted {} expired inbox rows", deleted)
    }

    /** Read-log cleanup is bounded by the monthly partition drop — see RetentionService. */
}
