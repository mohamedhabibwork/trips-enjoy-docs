package com.trips_enjoy.driver.application

import com.trips_enjoy.driver.domain.IdempotencyKey
import com.trips_enjoy.driver.domain.IdempotencyKeyRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * The Idempotency-Key middleware. One row per mutating REST call,
 * keyed on `(scope, idem_key)`. Replays return the cached response.
 *
 * The IdempotencyService is the canonical pattern from
 * customer-service / ledger-service / payment-service / audit-service
 * / reporting-service. See ADR-0019 for the platform-wide contract.
 */
@Service
class IdempotencyService(
    private val repository: IdempotencyKeyRepository,
) {
    @Transactional(readOnly = true)
    fun findExisting(scope: String, idemKey: String): IdempotencyKey? =
        repository.findByScopeAndIdemKey(scope, idemKey)

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