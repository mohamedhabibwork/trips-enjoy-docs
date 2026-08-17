package com.trips_enjoy.notification.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Admin-managed global suppression per docs/services/notification-service/ERD.md.
 * When a delivery's `category` matches a non-deleted suppression row whose
 * `expires_at` is NULL or in the future, the send path emits
 * `notification.suppressed.v1` with `reason = GLOBAL_SUPPRESSION`.
 */
@Entity
@Table(name = "suppressions", schema = "notification")
class Suppression(
	@Id
	val id: UUID,

	@Column(nullable = false)
	val category: String,

	@Column(nullable = false)
	val reason: String,

	@Column(name = "expires_at")
	val expiresAt: Instant? = null,

	@Column(name = "created_at", nullable = false)
	val createdAt: Instant = Instant.now(),

	@Column(name = "created_by", nullable = false)
	val createdBy: UUID,

	@Column(name = "deleted_at")
	var deletedAt: Instant? = null,
)