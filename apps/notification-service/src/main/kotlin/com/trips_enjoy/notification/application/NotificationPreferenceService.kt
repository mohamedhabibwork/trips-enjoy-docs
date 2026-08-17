package com.trips_enjoy.notification.application

import com.trips_enjoy.notification.domain.Preference
import com.trips_enjoy.notification.domain.PreferenceRepository
import com.trips_enjoy.notification.domain.enums.Channel
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Per-(user, category, channel) preference service per
 * docs/services/notification-service/SRS.md §FR-011,013 and WORKFLOWS §3.
 *
 *  - GET /v1/preferences/{user_id} (cacheable; invalidated on PATCH).
 *  - PATCH /v1/preferences/{user_id} UPSERT a row per channel+category.
 *  - Right-to-erasure anonymises rows for the user_id.
 *
 * Phase C (platform DRY): the audit fields (`id`, `createdAt`,
 * `updatedAt`, `createdBy`, `updatedBy`, `version`, `deletedAt`) are
 * inherited from `BaseEntity`. `createdBy` / `updatedBy` are now
 * populated by `PlatformAuditorAware` from the JWT `sub` and stored as
 * `String?`. `actorId` is kept on the method signature for the existing
 * callers / event-emission contract but is no longer written onto the
 * row by hand.
 */
@Service
class NotificationPreferenceService(private val prefs: PreferenceRepository) {

	@Cacheable(cacheNames = ["notification-preferences"], key = "#userId.toString()")
	@Transactional(readOnly = true)
	fun findForUser(userId: UUID): List<Preference> =
		prefs.findByUserIdAndDeletedAtIsNull(userId)

	@CacheEvict(cacheNames = ["notification-preferences"], key = "#userId.toString()")
	@Transactional
	fun upsert(
		userId: UUID,
		category: String,
		channel: Channel,
		optIn: Boolean,
		quietHoursStart: Int? = null,
		quietHoursEnd: Int? = null,
		timezone: String = "UTC",
		actorId: UUID,
	): Preference {
		val existing = prefs.findByUserIdAndCategoryAndChannelAndDeletedAtIsNull(userId, category, channel.value)
		return if (existing == null) {
			val created = Preference(
				userId = userId,
				category = category,
				channel = channel,
				optIn = optIn,
				quietHoursStart = quietHoursStart,
				quietHoursEnd = quietHoursEnd,
				timezone = timezone,
			)
			prefs.save(created)
		} else {
			existing.optIn = optIn
			existing.quietHoursStart = quietHoursStart
			existing.quietHoursEnd = quietHoursEnd
			existing.timezone = timezone
			existing
		}
	}

	@Transactional
	fun anonymiseForUser(userId: UUID) {
		prefs.findByUserIdAndDeletedAtIsNull(userId).forEach { it.deletedAt = Instant.now() }
	}
}