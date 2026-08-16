package com.trips_enjoy.notification.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * Transactional outbox for produced events per
 * docs/architecture/EVENT_ARCHITECTURE.md §7. Producers write an
 * `OutboxEvent` row in the same DB transaction as the business state change;
 * `OutboxPublisher` polls for unpublished rows and emits them to Kafka.
 *
 * The `payload` is pre-serialised JSON (envelope per EVENT_ARCHITECTURE.md §2):
 * event_id / event_name / occurred_at / schema_version / producer /
 * tenant_id / correlation_id / causation_id / aggregate_type / aggregate_id / data.
 */
@Entity
@Table(name = "outbox", schema = "notification")
class OutboxEvent(
	@Id
	val id: UUID,

	@Column(name = "aggregate_type", nullable = false)
	val aggregateType: String,

	@Column(name = "aggregate_id")
	val aggregateId: UUID? = null,

	@Column(nullable = false)
	val topic: String,

	@Column(name = "event_name", nullable = false)
	val eventName: String,

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	val payload: String,

	@Column(name = "created_at", nullable = false)
	val createdAt: Instant = Instant.now(),

	@Column(name = "published_at")
	var publishedAt: Instant? = null,

	@Column(nullable = false)
	var attempts: Int = 0,

	@Column(name = "last_error")
	var lastError: String? = null,
)