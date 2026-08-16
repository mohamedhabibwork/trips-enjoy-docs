package com.trips_enjoy.notification.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import jakarta.persistence.LockModeType
import java.time.Instant
import java.util.UUID

/**
 * All Spring Data JPA repositories for notification-service in one file,
 * matching the audit-service/identity-service convention. Each is a
 * `JpaRepository<Entity, ID>` extension with the minimum finder methods
 * needed by application services and scheduled jobs.
 */

@Repository
interface TemplateRepository : JpaRepository<Template, UUID> {
	fun findByNameAndChannelAndLocaleAndStatusAndDeletedAtIsNull(
		name: String,
		channel: String,
		locale: String,
		status: String = "active",
	): List<Template>

	@Query(
		"SELECT t FROM Template t WHERE t.name = :name AND t.channel = :channel " +
			"AND t.locale = :locale AND t.status = 'active' AND t.deletedAt IS NULL " +
			"ORDER BY t.version DESC",
	)
	fun findActiveLatest(
		@Param("name") name: String,
		@Param("channel") channel: String,
		@Param("locale") locale: String,
	): Template?
}

@Repository
interface TemplateHistoryRepository : JpaRepository<TemplateHistory, UUID> {
	fun findByTemplateIdOrderByRevisionNoDesc(templateId: UUID): List<TemplateHistory>

	@Query("SELECT COALESCE(MAX(h.revisionNo), 0) FROM TemplateHistory h WHERE h.templateId = :templateId")
	fun maxRevisionNo(@Param("templateId") templateId: UUID): Int
}

@Repository
interface PreferenceRepository : JpaRepository<Preference, UUID> {
	fun findByUserIdAndDeletedAtIsNull(userId: UUID): List<Preference>

	fun findByUserIdAndCategoryAndChannelAndDeletedAtIsNull(
		userId: UUID,
		category: String,
		channel: String,
	): Preference?
}

@Repository
interface SuppressionRepository : JpaRepository<Suppression, UUID> {
	@Query(
		"SELECT s FROM Suppression s WHERE s.category = :category AND s.deletedAt IS NULL " +
			"AND (s.expiresAt IS NULL OR s.expiresAt > :now)",
	)
	fun findActiveForCategory(
		@Param("category") category: String,
		@Param("now") now: Instant,
	): List<Suppression>
}

@Repository
interface DeliveryRepository : JpaRepository<Delivery, Delivery.Pk> {
	fun findByIdAndCreatedAt(id: UUID, createdAt: Instant): Delivery?

	fun findByCorrelationId(correlationId: UUID): List<Delivery>
}

@Repository
interface InboxEventRepository : JpaRepository<InboxEvent, UUID> {
	fun existsByEventIdAndConsumer(eventId: UUID, consumer: String): Boolean
}

@Repository
interface OutboxEventRepository : JpaRepository<OutboxEvent, UUID> {
	fun findTop100ByPublishedAtIsNullOrderByCreatedAtAsc(): List<OutboxEvent>
}

@Repository
interface IdempotencyRecordRepository : JpaRepository<IdempotencyRecord, UUID> {
	fun findByActorIdAndIdempotencyKey(actorId: UUID, idempotencyKey: UUID): IdempotencyRecord?

	@Query("DELETE FROM IdempotencyRecord r WHERE r.expiresAt < :now")
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	fun deleteExpired(@Param("now") now: Instant): Int
}