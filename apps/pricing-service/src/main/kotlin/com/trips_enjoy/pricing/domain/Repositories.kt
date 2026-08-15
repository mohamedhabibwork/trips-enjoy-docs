package com.trips_enjoy.pricing.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface QuoteCacheRepository : JpaRepository<QuoteCache, UUID> {
    fun findByCustomerIdOrderByCreatedAtDesc(customerId: UUID): List<QuoteCache>
    fun findByStatusAndExpiresAtBefore(status: String, expiresAt: Instant): List<QuoteCache>
}

@Repository
interface IdempotencyKeyRepository : JpaRepository<IdempotencyKey, UUID> {
    fun findByExpiresAtBefore(expiresAt: Instant): List<IdempotencyKey>
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

@Repository
interface SurgeCacheRepository : JpaRepository<SurgeCache, UUID>

@Repository
interface RatingDensityCacheRepository : JpaRepository<RatingDensityCache, RatingDensityCacheKey> {
    fun findByZoneId(zoneId: UUID): List<RatingDensityCache>
}

@Repository
interface LoyaltyFrequentCacheRepository : JpaRepository<LoyaltyFrequentCache, LoyaltyFrequentCacheKey> {
    fun findByCustomerId(customerId: UUID): List<LoyaltyFrequentCache>
}

@Repository
interface RuleBindingRepository : JpaRepository<RuleBinding, UUID> {
    fun findByTenantIdAndCityIdAndSupersededByIdIsNull(tenantId: String, cityId: String?): List<RuleBinding>

    @Query("SELECT r FROM RuleBinding r WHERE r.supersededById IS NULL AND (r.effectiveTo IS NULL OR r.effectiveTo > :now)")
    fun findActive(@Param("now") now: Instant): List<RuleBinding>
}

@Repository
interface GeoOverrideRepository : JpaRepository<GeoOverride, UUID> {
    fun findByOriginZoneIdAndDestinationZoneIdAndRideType(
        originZoneId: UUID, destinationZoneId: UUID, rideType: String,
    ): List<GeoOverride>
}

@Repository
interface RuleBindingsHistoryRepository : JpaRepository<RuleBindingsHistory, UUID> {
    fun findByBindingIdOrderByCreatedAtDesc(bindingId: UUID): List<RuleBindingsHistory>
}