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
 * Idempotency-Key dedupe using the **newer platform pattern** (vs the
 * legacy scope+key pattern used by other graduates): the
 * `idempotency_key` is itself the PK. Mirrors `pricing.idempotency`
 * per docs/services/pricing-service/ERD.md §3.
 *
 * Why the newer pattern: pricing-service has high QPS (every ride +
 * food-order needs a quote), so a single UUIDv7 PK is faster than a
 * composite `(scope, key)` unique index. A future graduate of the
 * other services can migrate to this pattern for the same reason.
 */
@Entity
@Table(name = "idempotency", schema = "pricing")
class IdempotencyKey(
    @Id @Column(name = "idempotency_key") val idempotencyKey: UUID,
    @Column(name = "request_hash", nullable = false) val requestHash: String,
    @Column(name = "response_status", nullable = false) var responseStatus: Int,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body", nullable = false, columnDefinition = "jsonb") var responseBody: Map<String, Any?>,
    @Column(name = "actor_id", nullable = false) val actorId: UUID,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "expires_at", nullable = false) val expiresAt: Instant,
) {
    init {
        require(requestHash.length == 64) { "request_hash must be a SHA-256 hex (64 chars)" }
        require(expiresAt.isAfter(createdAt)) { "expires_at must be after created_at" }
    }

    fun isExpired(at: Instant = Instant.now()): Boolean = !expiresAt.isAfter(at)
}