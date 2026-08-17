package com.trips_enjoy.pricing.application

import com.trips_enjoy.pricing.domain.IdempotencyKey
import com.trips_enjoy.pricing.domain.IdempotencyKeyRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Idempotency-Key middleware using the **newer platform pattern**:
 * `idempotency_key` is itself the PK (vs the legacy scope+key pattern).
 * The pricing-service has high QPS so a single UUIDv7 PK is faster
 * than a composite unique index.
 *
 * Lift-forward target: customer-service + driver-service + courier-service
 * + restaurant-service + payment-service may migrate to this pattern.
 */
@Service
class IdempotencyService(
    private val repository: IdempotencyKeyRepository,
) {
    @Transactional(readOnly = true)
    fun findExisting(idempotencyKey: UUID): IdempotencyKey? =
        repository.findById(idempotencyKey).orElse(null)

    @Transactional
    fun record(
        idempotencyKey: UUID,
        requestHash: String,
        responseStatus: Int,
        responseBody: Map<String, Any?>,
        actorId: UUID,
        ttlSeconds: Long = 86400,  // 24h default
        at: Instant = Instant.now(),
    ) {
        require(repository.findById(idempotencyKey).isEmpty) {
            "idempotency key $idempotencyKey already recorded"
        }
        val row = IdempotencyKey(
            idempotencyKey = idempotencyKey,
            requestHash = requestHash,
            responseStatus = responseStatus,
            responseBody = responseBody,
            actorId = actorId,
            createdAt = at,
            expiresAt = at.plusSeconds(ttlSeconds),
        )
        repository.save(row)
    }
}