package com.trips_enjoy.notification.domain

import com.trips_enjoy.notification.domain.enums.Channel
import com.trips_enjoy.platform.data.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
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
 *
 * Phase C (platform DRY): extends [BaseEntity] so the `id`, `createdAt`,
 * `updatedAt`, `createdBy`, `updatedBy`, `version`, and `deletedAt`
 * columns are inherited from the platform canonical shape. The
 * corresponding column migration is V10 (`created_by` / `updated_by`
 * `UUID` → `VARCHAR(255)`, `version BIGINT NOT NULL DEFAULT 0` added
 * for the `BaseEntity` optimistic-lock counter).
 *
 * `createdBy` / `updatedBy` are now populated by
 * `PlatformAuditorAware<String>` from the JWT `sub` claim and stored
 * as `String?`.
 */
@Entity
@Table(name = "preferences", schema = "notification")
class Preference(
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
) : BaseEntity()