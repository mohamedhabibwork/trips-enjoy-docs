package com.trips_enjoy.admin.application

import com.trips_enjoy.admin.domain.IdempotencyKey
import com.trips_enjoy.admin.domain.IdempotencyKeyRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

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