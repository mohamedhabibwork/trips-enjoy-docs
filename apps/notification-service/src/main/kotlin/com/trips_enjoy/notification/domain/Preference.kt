package com.trips_enjoy.notification.domain

import com.trips_enjoy.notification.domain.enums.Channel
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Per-(user, category, channel) notification preference per
 * docs/services/notification-service/ERD.md.
 *
 *  - `opt_in = false` ⇒ user has unsubscribed; send path emits
 *    `notification.suppressed.v1` with `reason = OPTED_OUT`.
 *  - `quiet_hours_start`/`quiet_hours_end` are local hours in `timezone`
 *    (defaults to UTC). Quiet hours are bypassed for `priority = urgent`
 *    notifications (safety / SOS / fraud alert).
 *  - Soft-delete via `deleted_at`; UNIQUE(user_id, category, channel) is
 *    partial WHERE deleted_at IS NULL.
 *  - Right-to-erasure anonymises this table (`deleted_at = now()`,
 *    `opt_in` left at the recorded value).
 */
@Entity
@Table(name = "preferences", schema = "notification")
class Preference(
	@Id
	val id: UUID,

	@Column(name = "user_id", nullable = false)
	val userId: UUID,

	@Column(nullable = false)
	val category: String,

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	val channel: Channel,

	@Column(name = "opt_in", nullable = false)
	var optIn: Boolean = true,

	@Column(name = "quiet_hours_start")
	var quietHoursStart: Int? = null,

	@Column(name = "quiet_hours_end")
	var quietHoursEnd: Int? = null,

	@Column(nullable = false)
	var timezone: String = "UTC",

	@Column(name = "created_at", nullable = false)
	val createdAt: Instant = Instant.now(),

	@Column(name = "updated_at", nullable = false)
	var updatedAt: Instant = Instant.now(),

	@Column(name = "created_by")
	val createdBy: UUID? = null,

	@Column(name = "updated_by")
	var updatedBy: UUID? = null,

	@Column(name = "deleted_at")
	var deletedAt: Instant? = null,
)