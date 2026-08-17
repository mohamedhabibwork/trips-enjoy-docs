package com.trips_enjoy.customer.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.customer.domain.Customer
import com.trips_enjoy.customer.domain.CustomerAuditLog
import com.trips_enjoy.customer.domain.CustomerAuditLogRepository
import com.trips_enjoy.customer.domain.CustomerRepository
import com.trips_enjoy.customer.domain.CustomerSegmentHistory
import com.trips_enjoy.customer.domain.CustomerSegmentHistoryRepository
import com.trips_enjoy.customer.util.uuidV7
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Segment recomputation (INTEGRATION.md §4.9 / WORKFLOWS.md §5).
 *
 * Called inline from LtvUpdateService (trigger = `ltv_change`) and from
 * the nightly cron job (trigger = `nightly_job`). Rules:
 *   - `frequent` if `rides_this_month >= frequent_rides` and `vip_ltv_minor`
 *     threshold not crossed.
 *   - `vip` if `ltv_minor >= vip_ltv_minor`.
 *   - `churned` if `last_active_at` is older than `churned_idle_days`.
 *   - otherwise `standard`.
 *
 * Thresholds and idle-days are read from config (default to the
 * platform baselines documented in README §13). The recompute is
 * idempotent: a no-op segment keeps the existing row, only changes
 * write a row + emit an event.
 */
@Service
class SegmentRecomputer(
    private val customerRepository: CustomerRepository,
    private val segmentHistoryRepository: CustomerSegmentHistoryRepository,
    private val auditLogRepository: CustomerAuditLogRepository,
    private val readService: CustomerReadService,
    private val eventPublisher: EventPublisher,
    private val mapper: ObjectMapper,
    @Value("\${customer-service.segment.frequent-rides:20}") private val frequentRides: Int,
    @Value("\${customer-service.segment.vip-ltv-minor:1000000}") private val vipLtvMinor: Long,
    @Value("\${customer-service.segment.churned-idle-days:90}") private val churnedIdleDays: Int,
) {
    /**
     * Recompute segment for a single customer. Called inline from
     * LtvUpdateService. The caller is expected to hold the row lock.
     */
    @Transactional
    fun recompute(
        customer: Customer,
        trigger: String,
        correlationId: UUID,
    ): Customer {
        val newSegment = computeSegment(customer)
        if (newSegment == customer.segment) return customer
        val before = segmentSnapshot(customer)
        val fromSegment = customer.segment
        customer.segment = newSegment
        customer.segmentUpdatedAt = Instant.now()
        customerRepository.save(customer)
        val customerId = requireNotNull(customer.id) { "Customer.id must be assigned after save" }
        segmentHistoryRepository.save(
            CustomerSegmentHistory(
                id = uuidV7(),
                customerId = customerId,
                fromSegment = fromSegment,
                toSegment = newSegment,
                trigger = trigger,
            ),
        )
        auditLogRepository.save(
            CustomerAuditLog(
                id = uuidV7(),
                customerId = customerId,
                action = "segment_change",
                actor = null,
                actorType = "system",
                before = mapper.writeValueAsString(before),
                after = mapper.writeValueAsString(segmentSnapshot(customer)),
                reason = "trigger=$trigger $fromSegment -> $newSegment",
                correlationId = correlationId,
            ),
        )
        eventPublisher.publish(
            topic = "customer.segment.changed",
            eventName = "customer.segment.changed.v1",
            aggregateType = "Customer",
            aggregateId = customerId,
            data =
                mapOf(
                    "customer_id" to customerId.toString(),
                    "from_segment" to fromSegment,
                    "to_segment" to newSegment,
                    "trigger" to trigger,
                    "occurred_at" to customer.segmentUpdatedAt.toString(),
                ),
            correlationId = correlationId,
        )
        readService.invalidate(customerId)
        return customer
    }

    /**
     * Nightly job OVERRIDE — re-evaluates every active customer once a
     * day so ride-count rollovers and idle-time churned detection are
     * kept current (the inline LTV path only sees LTV-driven changes).
     */
    @Scheduled(cron = "\${customer-service.segment.nightly-cron:0 30 2 * * *}")
    @Transactional
    fun nightlyRecompute() {
        val correlationId = UUID.randomUUID()
        val customers = customerRepository.findAllActive()
        var changed = 0
        for (customer in customers) {
            val before = customer.segment
            val updated = recompute(customer, trigger = "nightly_job", correlationId = correlationId)
            if (updated.segment != before) changed += 1
        }
        if (changed > 0) {
            // Use slf4j via the running app's logger; lightweight for tests.
            println("SegmentRecomputer: nightly recompute updated $changed customers")
        }
    }

    /**
     * Public so tests can verify the deterministic mapping.
     */
    fun computeSegment(customer: Customer): String {
        val now = Instant.now()
        if (customer.lastActiveAt != null &&
            Duration.between(customer.lastActiveAt, now).toDays() >= churnedIdleDays
        ) {
            return "churned"
        }
        if (customer.ltvMinor >= vipLtvMinor) return "vip"
        if (customer.ridesThisMonth >= frequentRides) return "frequent"
        return "standard"
    }

    private fun segmentSnapshot(customer: Customer): Map<String, Any?> =
        mapOf(
            "id" to customer.id?.toString(),
            "segment" to customer.segment,
            "rides_this_month" to customer.ridesThisMonth,
            "ltv_minor" to customer.ltvMinor,
            "row_version" to customer.version,
        )
}
