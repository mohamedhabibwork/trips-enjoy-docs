package com.trips_enjoy.customer.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.customer.api.ApiException
import com.trips_enjoy.customer.domain.Customer
import com.trips_enjoy.customer.domain.CustomerAuditLog
import com.trips_enjoy.customer.domain.CustomerAuditLogRepository
import com.trips_enjoy.customer.domain.CustomerLtvHistory
import com.trips_enjoy.customer.domain.CustomerLtvHistoryPk
import com.trips_enjoy.customer.domain.CustomerLtvHistoryRepository
import com.trips_enjoy.customer.domain.CustomerRepository
import com.trips_enjoy.customer.util.uuidV7
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * LTV update path (INTEGRATION.md §4.9 / §4.10 / WORKFLOWS.md §4).
 *
 * On `ride.payment.completed.v1` or `food.payment.completed.v1` the
 * service increments `customers.ltv_minor` by the payload amount and
 * writes a delta row to `customer_ltv_history`. The currency is single-
 * per-customer (set at `customers.ltv_currency`); a mismatch returns
 * 422 CURRENCY_MISMATCH.
 *
 * After the increment the segment recomputer (`SegmentRecomputer`) is
 * invoked in the same transaction; a segment change emits
 * `customer.segment.changed.v1`.
 *
 * Concurrency: row-level lock on the customer row (`lockById`) so
 * concurrent payment events for the same customer serialize per
 * SRS §14.
 */
@Service
class LtvUpdateService(
    private val customerRepository: CustomerRepository,
    private val ltvHistoryRepository: CustomerLtvHistoryRepository,
    private val auditLogRepository: CustomerAuditLogRepository,
    private val segmentRecomputer: SegmentRecomputer,
    private val eventPublisher: EventPublisher,
    private val mapper: ObjectMapper,
) {
    @Transactional
    fun applyPayment(
        customerId: UUID,
        amountMinor: Long,
        currency: String,
        service: String,
        requestId: UUID?,
        correlationId: UUID,
    ): Customer {
        if (amountMinor <= 0) {
            throw ApiException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "amount_minor must be > 0",
            )
        }
        if (service !in listOf("ride", "food", "adjustment", "refund")) {
            throw ApiException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "service must be one of ride/food/adjustment/refund",
            )
        }
        val customer =
            customerRepository.lockById(customerId).orElseThrow {
                ApiException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Customer $customerId not found")
            }
        if (customer.status == "erased") {
            throw ApiException(HttpStatus.CONFLICT, "CUSTOMER_ERASED", "Customer $customerId has been erased")
        }
        if (customer.ltvCurrency != currency) {
            throw ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "CURRENCY_MISMATCH",
                "Customer currency is ${customer.ltvCurrency} but event currency is $currency",
            )
        }
        val before = snapshot(customer)
        val delta = if (service == "refund") -amountMinor else amountMinor
        customer.ltvMinor = (customer.ltvMinor + delta).coerceAtLeast(0L)
        customer.ltvUpdatedAt = Instant.now()
        customer.lastActiveAt = Instant.now()
        if (service == "ride") {
            customer.ridesThisMonth = customer.ridesThisMonth + 1
        }
        customer.rowVersion = customer.rowVersion + 1
        customer.updatedAt = Instant.now()
        customerRepository.save(customer)
        val occurredAt = Instant.now()
        ltvHistoryRepository.save(
            CustomerLtvHistory(
                pk = CustomerLtvHistoryPk(id = uuidV7(), occurredAt = occurredAt),
                customerId = customerId,
                deltaMinor = delta,
                currency = currency,
                service = service,
                requestId = requestId,
            ),
        )
        auditLogRepository.save(
            CustomerAuditLog(
                id = uuidV7(),
                customerId = customerId,
                action = "ltv_change",
                actor = null,
                actorType = "system",
                before = mapper.writeValueAsString(before),
                after = mapper.writeValueAsString(snapshot(customer)),
                reason = "service=$service delta=$delta",
                correlationId = correlationId,
            ),
        )
        eventPublisher.publish(
            topic = "customer.updated",
            eventName = "customer.updated.v1",
            aggregateType = "Customer",
            aggregateId = customer.id,
            data =
                mapOf(
                    "customer_id" to customer.id.toString(),
                    "ltv_minor" to customer.ltvMinor,
                    "ltv_currency" to customer.ltvCurrency,
                    "changed_fields" to listOf("ltv_minor"),
                    "occurred_at" to occurredAt.toString(),
                ),
            correlationId = correlationId,
        )
        // Re-evaluate the segment in the same transaction so the
        // downstream consumer cannot race the LTV update.
        segmentRecomputer.recompute(customer, trigger = "ltv_change", correlationId = correlationId)
        return customer
    }

    private fun snapshot(customer: Customer): Map<String, Any?> =
        mapOf(
            "id" to customer.id.toString(),
            "ltv_minor" to customer.ltvMinor,
            "ltv_currency" to customer.ltvCurrency,
            "segment" to customer.segment,
            "rides_this_month" to customer.ridesThisMonth,
            "row_version" to customer.rowVersion,
        )
}
