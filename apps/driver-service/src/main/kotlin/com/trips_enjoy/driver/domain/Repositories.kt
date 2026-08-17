package com.trips_enjoy.driver.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * Spring Data JPA repositories for the driver-service aggregates.
 * Mirrors the customer-service + payment-service pattern. All write
 * paths go through the application service layer (which uses
 * @Transactional); the repositories are thin Spring Data interfaces.
 */

@Repository
interface DriverRepository : JpaRepository<Driver, UUID> {
    fun findByIdentityIdAndDeletedAtIsNull(identityId: UUID): Driver?
    fun findByStatusAndDeletedAtIsNull(status: String): List<Driver>
    fun findByIdAndDeletedAtIsNull(id: UUID): Driver?
}

@Repository
interface DriverDocumentRepository : JpaRepository<DriverDocument, UUID> {
    fun findByDriverIdAndDeletedAtIsNull(driverId: UUID): List<DriverDocument>
    fun findByDriverIdAndStatusAndDeletedAtIsNull(driverId: UUID, status: String): List<DriverDocument>
    fun findByExpiryDateBeforeAndStatusAndDeletedAtIsNull(
        expiryDate: Instant, status: String,
    ): List<DriverDocument>
}

@Repository
interface DriverCityEligibilityRepository : JpaRepository<DriverCityEligibility, UUID> {
    fun findByDriverIdAndRevokedAtIsNull(driverId: UUID): List<DriverCityEligibility>

    @Query("SELECT e FROM DriverCityEligibility e WHERE e.driverId = :driverId AND e.cityId = :cityId AND e.revokedAt IS NULL")
    fun findActive(driverId: UUID, cityId: UUID): DriverCityEligibility?
}

@Repository
interface DriverRatingHistoryRepository : JpaRepository<DriverRatingHistory, UUID> {
    fun findByDriverIdOrderByRatedAtDesc(driverId: UUID): List<DriverRatingHistory>
}

@Repository
interface DriverAuditLogRepository : JpaRepository<DriverAuditLog, UUID> {
    fun findByDriverIdOrderByCreatedAtDesc(driverId: UUID): List<DriverAuditLog>
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