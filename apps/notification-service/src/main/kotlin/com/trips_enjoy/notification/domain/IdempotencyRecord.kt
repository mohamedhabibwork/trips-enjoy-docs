package com.trips_enjoy.notification.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Idempotency-Key dedup table per docs/architecture/API_STANDARDS.md §9 +
 * docs/services/notification-service/SRS.md §FR--016.
 *
 *  - Same key + same body hash → return the stored response (24h).
 *  - Same key + different body hash → 422 `IDEMPOTENCY_KEY_REUSED`.
 *  - Cleanup: `IdempotencyCleanupJob` deletes rows WHERE expires_at < now().
 */
@Entity
@Table(name = "idempotency_records", schema = "notification")
class IdempotencyRecord(
	@Id
	val id: UUID,

	@Column(name = "actor_id", nullable = false)
	val actorId: UUID,

	@Column(name = "idempotency_key", nullable = false)
	val idempotencyKey: UUID,

	@Column(name = "request_hash", nullable = false)
	val requestHash: String,

	@Column(name = "response_status", nullable = false)
	val responseStatus: Int,

	@Column(name = "response_body", nullable = false, columnDefinition = "text")
	val responseBody: String,

	@Column(name = "expires_at", nullable = false)
	val expiresAt: Instant,

	@Column(name = "created_at", nullable = false)
	val createdAt: Instant = Instant.now(),
)