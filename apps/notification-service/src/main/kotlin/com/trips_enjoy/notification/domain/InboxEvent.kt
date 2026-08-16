package com.trips_enjoy.notification.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Consumer-side dedup table per docs/architecture/EVENT_ARCHITECTURE.md §9.
 * Keyed by `event_id` (UUID); INSERT-noop on duplicate, then process, then
 * update `processed_at`. The `consumer` column names the worker class so
 * the same `event_id` may be re-processed by a different consumer.
 */
@Entity
@Table(name = "inbox", schema = "notification")
class InboxEvent(
	@Id
	@Column(name = "event_id")
	val eventId: UUID,

	@Column(nullable = false)
	val topic: String,

	@Column(name = "consumer", nullable = false)
	val consumer: String,

	@Column(name = "received_at", nullable = false)
	val receivedAt: Instant = Instant.now(),

	@Column(name = "processed_at")
	var processedAt: Instant? = null,

	@Column
	var error: String? = null,
)