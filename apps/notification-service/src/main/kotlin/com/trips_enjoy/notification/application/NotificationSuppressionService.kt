package com.trips_enjoy.notification.application

import com.trips_enjoy.notification.domain.Suppression
import com.trips_enjoy.notification.domain.SuppressionRepository
import com.trips_enjoy.notification.util.uuidV7
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Admin-managed global suppression per
 * docs/services/notification-service/ERD.md + WORKFLOWS §1.
 *
 *  - `findActive` returns the rows that apply right now (category match,
 *    `deleted_at IS NULL`, `expires_at` either NULL or in the future).
 *  - The cached lookup is invalidated by the SoftDelete in the apply path
 *    would normally require `@CacheEvict` — implemented through Spring's
 *    CacheManager in the controller layer.
 */
@Service
class NotificationSuppressionService(private val suppressions: SuppressionRepository) {

	@Cacheable(cacheNames = ["notification-suppressions"], key = "#category")
	@Transactional(readOnly = true)
	fun findActive(category: String): List<Suppression> =
		suppressions.findActiveForCategory(category, Instant.now())

	@Transactional
	fun create(category: String, reason: String, expiresAt: Instant?, actorId: UUID): Suppression {
		val created = Suppression(
			id = uuidV7(),
			category = category,
			reason = reason,
			expiresAt = expiresAt,
			createdAt = Instant.now(),
			createdBy = actorId,
		)
		return suppressions.save(created)
	}

	@Transactional
	fun delete(suppressionId: UUID) {
		val s = suppressions.findById(suppressionId).orElse(null) ?: return
		s.deletedAt = Instant.now()
	}

	@Transactional(readOnly = true)
	fun list(): List<Suppression> = suppressions.findAll()
}