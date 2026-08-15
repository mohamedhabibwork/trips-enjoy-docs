package com.trips_enjoy.courier.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface CourierRepository : JpaRepository<Courier, UUID> {
    fun findByIdentityIdAndDeletedAtIsNull(identityId: UUID): Courier?
    fun findByStatusAndDeletedAtIsNull(status: String): List<Courier>
    fun findByIdAndDeletedAtIsNull(id: UUID): Courier?
}

@Repository
interface CourierDocumentRepository : JpaRepository<CourierDocument, UUID> {
    fun findByCourierIdAndDeletedAtIsNull(courierId: UUID): List<CourierDocument>
    fun findByCourierIdAndStatusAndDeletedAtIsNull(courierId: UUID, status: String): List<CourierDocument>
}

@Repository
interface CourierShiftRepository : JpaRepository<CourierShift, UUID> {
    fun findByCourierIdAndDeletedAtIsNull(courierId: UUID): List<CourierShift>

    @Query("SELECT s FROM CourierShift s WHERE s.courierId = :courierId AND s.status = 'active' AND s.deletedAt IS NULL")
    fun findActive(courierId: UUID): CourierShift?
}

@Repository
interface CourierCityEligibilityRepository : JpaRepository<CourierCityEligibility, UUID> {
    fun findByCourierIdAndRevokedAtIsNull(courierId: UUID): List<CourierCityEligibility>

    @Query("SELECT e FROM CourierCityEligibility e WHERE e.courierId = :courierId AND e.cityId = :cityId AND e.revokedAt IS NULL")
    fun findActive(courierId: UUID, cityId: UUID): CourierCityEligibility?
}

@Repository
interface CourierRatingHistoryRepository : JpaRepository<CourierRatingHistory, UUID> {
    fun findByCourierIdOrderByRatedAtDesc(courierId: UUID): List<CourierRatingHistory>
}

@Repository
interface CourierAuditLogRepository : JpaRepository<CourierAuditLog, UUID> {
    fun findByCourierIdOrderByCreatedAtDesc(courierId: UUID): List<CourierAuditLog>
}

@Repository
interface IdempotencyKeyRepository : JpaRepository<IdempotencyKey, UUID> {
    fun findByScopeAndIdemKey(scope: String, idemKey: String): IdempotencyKey?
}

@Repository
interface OutboxEventRepository : JpaRepository<OutboxEvent, UUID> {
    @Query("SELECT o FROM OutboxEvent o WHERE o.publishedAt IS NULL AND o.nextAttemptAt <= :now ORDER BY o.nextAttemptAt ASC")
    fun findPending(@Param("now") now: Instant, pageable: org.springframework.data.domain.Pageable): List<OutboxEvent>

    @Modifying
    @Query("DELETE FROM OutboxEvent o WHERE o.publishedAt IS NOT NULL AND o.publishedAt < :cutoff")
    fun deletePublishedBefore(@Param("cutoff") cutoff: Instant): Int
}

@Repository
interface InboxEventRepository : JpaRepository<InboxEvent, UUID> {
    fun findBySourceTopicAndSourceEventId(sourceTopic: String, sourceEventId: UUID): InboxEvent?

    @Modifying
    @Query("DELETE FROM InboxEvent i WHERE i.consumedAt < :cutoff")
    fun deleteConsumedBefore(@Param("cutoff") cutoff: Instant): Int
}