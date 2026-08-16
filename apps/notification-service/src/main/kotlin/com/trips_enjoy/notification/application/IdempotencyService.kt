package com.trips_enjoy.notification.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.notification.api.ApiException
import com.trips_enjoy.notification.domain.IdempotencyRecord
import com.trips_enjoy.notification.domain.IdempotencyRecordRepository
import com.trips_enjoy.notification.util.sha256Hex
import com.trips_enjoy.notification.util.uuidV7
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Idempotency-Key helper per docs/architecture/API_STANDARDS.md §9 and
 * docs/services/notification-service/SRS.md §FR--016.
 *
 * Pattern (mirrors identity-service's `IdentityApplicationService.idempotent`):
 *  1. Hash the request payload (SHA-256 hex).
 *  2. If `(actor, key)` exists with the same hash → return the stored response.
 *  3. If `(actor, key)` exists with a different hash → throw 422 IDEMPOTENCY_KEY_REUSED.
 *  4. Otherwise execute `action()`, persist the response for 24h, return it.
 *
 * 24h TTL matches API_STANDARDS.md §9 and SRS.md §FR--016.
 */
@Service
class IdempotencyService(
	private val records: IdempotencyRecordRepository,
	private val mapper: ObjectMapper,
) {

	@Transactional
	fun <T : Any> idempotent(
		actorId: UUID,
		idempotencyKey: UUID,
		request: Any,
		responseClass: Class<T>,
		action: () -> T,
	): T {
		val hash = sha256Hex(mapper.writeValueAsString(request))
		val existing = records.findByActorIdAndIdempotencyKey(actorId, idempotencyKey)
		if (existing != null) {
			if (existing.requestHash != hash) {
				throw ApiException(
					HttpStatus.UNPROCESSABLE_ENTITY,
					"IDEMPOTENCY_KEY_REUSED",
					"Idempotency-Key was used with a different request body",
				)
			}
			return mapper.readValue(existing.responseBody, responseClass)
		}
		val response = action()
		val record = IdempotencyRecord(
			id = uuidV7(),
			actorId = actorId,
			idempotencyKey = idempotencyKey,
			requestHash = hash,
			responseStatus = 200,
			responseBody = mapper.writeValueAsString(response),
			expiresAt = Instant.now().plus(24, ChronoUnit.HOURS),
		)
		records.save(record)
		return response
	}

	@Transactional(readOnly = true)
	fun lookup(actorId: UUID, idempotencyKey: UUID): IdempotencyRecord? =
		records.findByActorIdAndIdempotencyKey(actorId, idempotencyKey)
}