package com.trips_enjoy.payment.application

import com.trips_enjoy.payment.domain.IdempotencyKey
import com.trips_enjoy.payment.domain.IdempotencyKeyRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * The Idempotency-Key middleware. One row per mutating REST call,
 * keyed on `(scope, idem_key)`. Replays return the cached response.
 *
 * The IdempotencyService is the canonical pattern from
 * customer-service / ledger-service / audit-service / reporting-service
 * (see docs/services/customer-service/application/IdempotencyService.kt).
 * Per ADR-0019 the platform-wide Idempotency-Key contract lives here.
 */
@Service
class IdempotencyService(
    private val repository: IdempotencyKeyRepository,
) {
    /**
     * Look up an existing idempotency key. Returns `null` if the key
     * has never been seen; the caller proceeds with the operation.
     */
    @Transactional(readOnly = true)
    fun findExisting(scope: String, idemKey: String): IdempotencyKey? =
        repository.findByScopeAndIdemKey(scope, idemKey)

    /**
     * Record a new idempotency key + cached response. Throws if the key
     * is already recorded (the caller should have called `findExisting`
     * first). The row is written in the same transaction as the
     * aggregate mutation that the idempotent call produced.
     */
    @Transactional
    fun record(
        scope: String,
        idemKey: String,
        requestHash: String,
        responseStatus: Int,
        responseBody: Map<String, Any?>,
        createdBy: UUID,
        at: Instant = Instant.now(),
    ) {
        require(repository.findByScopeAndIdemKey(scope, idemKey) == null) {
            "idempotency key $scope:$idemKey already recorded"
        }
        val row = IdempotencyKey(
            id = UUID.randomUUID(),
            scope = scope,
            idemKey = idemKey,
            requestHash = requestHash,
            responseStatus = responseStatus,
            responseBody = responseBody,
            completedAt = at,
            createdBy = createdBy,
        )
        repository.save(row)
    }
}