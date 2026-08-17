package com.trips_enjoy.pricing.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * A short-lived cache of computed quotes. Mirrors `pricing.quote_cache`
 * per docs/services/pricing-service/ERD.md §3. Keyed by `quote_id`;
 * indexed by `(customer_id, created_at)` for replay.
 *
 * State machine:
 *   active   → consumed   (the consumer of the quote — trip-service or
 *                          food-order-service — marks it consumed)
 *   active   → expired    (the expiry job after `expires_at`)
 *   consumed → terminal
 *   expired  → terminal
 */
@Entity
@Table(name = "quote_cache", schema = "pricing")
class QuoteCache(
    @Id val id: UUID,
    @Column(name = "customer_id") val customerId: UUID? = null,
    @Column(name = "product_type", nullable = false) val productType: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb") val request: Map<String, Any?>,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb") val quote: Map<String, Any?>,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_snapshot", nullable = false, columnDefinition = "jsonb") val configSnapshot: Map<String, Any?>,
    @Column(nullable = false) var status: String = STATUS_ACTIVE,
    @Column(name = "expires_at", nullable = false) val expiresAt: Instant,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "consumed_at") var consumedAt: Instant? = null,
) {
    companion object {
        const val STATUS_ACTIVE = "active"
        const val STATUS_CONSUMED = "consumed"
        const val STATUS_EXPIRED = "expired"

        const val PRODUCT_RIDE = "ride"
        const val PRODUCT_FOOD = "food"

        val VALID_STATUSES: Set<String> = setOf(STATUS_ACTIVE, STATUS_CONSUMED, STATUS_EXPIRED)
        val VALID_PRODUCTS: Set<String> = setOf(PRODUCT_RIDE, PRODUCT_FOOD)
    }

    init {
        require(productType in VALID_PRODUCTS) { "unknown product_type $productType" }
        require(status in VALID_STATUSES) { "unknown status $status" }
        require(expiresAt.isAfter(createdAt)) { "expires_at must be after created_at" }
    }

    fun consume(at: Instant) {
        check(status == STATUS_ACTIVE) { "cannot consume quote in status $status" }
        require(at <= expiresAt) { "cannot consume an expired quote" }
        status = STATUS_CONSUMED
        consumedAt = at
    }

    fun expire(at: Instant) {
        check(status == STATUS_ACTIVE) { "cannot expire quote in status $status" }
        require(at >= expiresAt) { "expire() called before expires_at" }
        status = STATUS_EXPIRED
    }

    fun isActive(at: Instant = Instant.now()): Boolean =
        status == STATUS_ACTIVE && expiresAt.isAfter(at)

    fun isTerminal(): Boolean = status in setOf(STATUS_CONSUMED, STATUS_EXPIRED)
}