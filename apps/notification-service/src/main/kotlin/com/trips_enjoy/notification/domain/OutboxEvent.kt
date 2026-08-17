package com.trips_enjoy.notification.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * Notification-service outbox row — Phase B of the platform-DRY
 * initiative (ADR-0028): the local entity persists into the canonical
 * 11-column `notification.outbox` table.
 *
 * The 11 canonical columns (id, event_id, topic, partition_key,
 * payload, headers, created_at, published_at, attempts, last_error,
 * next_attempt_at) are written directly via JPA. The notification-
 * service service-local columns (aggregate_type, aggregate_id,
 * event_name, correlation_id, created_by) live alongside them in the
 * same table so the existing constructor contract stays stable for
 * callers.
 *
 * `event_id` and `partition_key` are auto-populated by `@PrePersist`.
 * The service-local fields are mirrored into the canonical `headers`
 * JSONB so downstream consumers see them without needing to know the
 * local schema.
 */
@Entity
@Table(name = "outbox", schema = "notification")
class OutboxEvent(
	@Id
	@Column(name = "id", nullable = false, updatable = false)
	val id: UUID,

	@Column(name = "aggregate_type", nullable = false)
	val aggregateType: String,

	@Column(name = "aggregate_id")
	val aggregateId: UUID? = null,

	@Column(name = "topic", nullable = false)
	val topic: String,

	@Column(name = "event_name", nullable = false)
	val eventName: String,

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "payload", nullable = false, columnDefinition = "jsonb")
	val payload: String,

	@Column(name = "created_at", nullable = false)
	val createdAt: Instant = Instant.now(),

	@Column(name = "published_at")
	var publishedAt: Instant? = null,

	@Column(name = "attempts", nullable = false)
	var attempts: Int = 0,

	@Column(name = "last_error")
	var lastError: String? = null,

	// ----- Canonical columns (auto-populated by @PrePersist) -----------

	@Column(name = "event_id", nullable = false, unique = true)
	var eventId: UUID = UUID.randomUUID(),

	@Column(name = "partition_key", nullable = false)
	var partitionKey: String = "notification",

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "headers", nullable = false, columnDefinition = "jsonb")
	var headers: String = "{}",

	@Column(name = "next_attempt_at", nullable = false)
	var nextAttemptAt: Instant = Instant.now(),

	@Column(name = "correlation_id", nullable = false)
	var correlationId: UUID = UUID.randomUUID(),

	@Column(name = "created_by", nullable = false)
	var createdBy: UUID = UUID.randomUUID(),
) {
	@PrePersist
	fun onPrePersist() {
		if (headers.isBlank() || headers == "{}") headers = headersJson()
		if (partitionKey.isBlank()) partitionKey = "notification"
	}

	init {
		if (headers.isBlank() || headers == "{}") headers = headersJson()
		if (partitionKey.isBlank()) partitionKey = "notification"
	}

	private fun headersJson(): String =
		"""{"aggregate_type":"${aggregateType.replace("\"", "\\\"")}","event_name":"${eventName.replace("\"", "\\\"")}"}"""

	fun markPublished(at: Instant) {
		publishedAt = at
	}

	fun markFailed(error: String, nextAttemptAt: Instant) {
		attempts += 1
		lastError = error
		this.nextAttemptAt = nextAttemptAt
	}
}
