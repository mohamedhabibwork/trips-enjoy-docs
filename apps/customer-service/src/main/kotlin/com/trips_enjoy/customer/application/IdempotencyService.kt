package com.trips_enjoy.customer.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.customer.domain.Idempotency
import com.trips_enjoy.customer.domain.IdempotencyRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * Idempotency-Key dedupe per SRS §15 / INTEGRATION.md §1.2-§1.9.
 *
 * A write request that carries an `Idempotency-Key` header is mapped to
 * the cached response (status + body). If the same key is reused with a
 * different request body, the caller gets IDEMPOTENCY_KEY_REUSED.
 */
@Service
class IdempotencyService(
    private val repository: IdempotencyRepository,
    private val mapper: ObjectMapper,
    @Value("\${customer-service.idempotency.ttl-seconds:86400}")
    private val ttlSeconds: Long,
) {
    @Transactional(readOnly = true)
    fun find(key: UUID): Optional<Idempotency> = repository.findById(key)

    @Transactional
    fun record(
        key: UUID,
        requestHash: String,
        actorId: UUID,
        responseStatus: Int,
        responseBody: Any,
    ) {
        val now = Instant.now()
        val body = mapper.writeValueAsString(responseBody)
        val row =
            Idempotency(
                idempotencyKey = key,
                requestHash = requestHash,
                responseStatus = responseStatus,
                responseBody = body,
                actorId = actorId,
                createdAt = now,
                expiresAt = now.plusSeconds(ttlSeconds),
            )
        repository.save(row)
    }

    @Transactional
    fun purgeExpired(): Long = repository.deleteAllByExpiresAtBefore(Instant.now())
}
