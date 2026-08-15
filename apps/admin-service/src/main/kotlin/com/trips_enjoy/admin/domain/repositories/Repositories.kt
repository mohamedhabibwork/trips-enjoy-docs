package com.trips_enjoy.admin.domain.repositories

import com.trips_enjoy.admin.domain.ActionLog
import com.trips_enjoy.admin.domain.ActionLogKey
import com.trips_enjoy.admin.domain.BreakGlass
import com.trips_enjoy.admin.domain.IdempotencyKey
import com.trips_enjoy.admin.domain.InboxEvent
import com.trips_enjoy.admin.domain.OutboxEvent
import com.trips_enjoy.admin.domain.PricingGeoConfig
import com.trips_enjoy.admin.domain.PricingGeoConfigHistory
import com.trips_enjoy.admin.domain.SuperAdminGrant
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface ActionLogRepository : JpaRepository<ActionLog, ActionLogKey> {
    @Query("SELECT a FROM ActionLog a WHERE a.id.id = :actorKcSub ORDER BY a.id.occurredAt DESC")
    fun findByActorKcSubOrderByOccurredAtDesc(actorKcSub: UUID): List<ActionLog>
}

@Repository
interface BreakGlassRepository : JpaRepository<BreakGlass, UUID> {
    fun findByActionLogId(actionLogId: UUID): BreakGlass?
    fun findByCosignerKcSubAndExpiresAtAfter(cosignerKcSub: UUID, after: Instant): List<BreakGlass>
}

@Repository
interface SuperAdminGrantRepository : JpaRepository<SuperAdminGrant, UUID> {
    companion object {
        /**
         * The canonical SUPER_ADMIN preset: 1 × platform.super_admin +
         * 20 × <service>.admin (one per graduated service).
         */
        val DEFAULT_PRESET_SCOPES: List<String> = listOf(
            "platform.super_admin",
            "admin.admin",
            "audit.admin",
            "configuration.admin",
            "customer.admin",
            "driver.admin",
            "courier.admin",
            "fraud-risk.admin",
            "file.admin",
            "geolocation.admin",
            "identity.admin",
            "ledger.admin",
            "notification.admin",
            "payment.admin",
            "pricing.admin",
            "reporting.admin",
            "restaurant.admin",
            "search.admin",
            "trip.admin",
            "food-order.admin",
            "chat.admin",
        )
    }

    fun findByGranteeKcSubAndRevokedAtIsNull(granteeKcSub: UUID): List<SuperAdminGrant>

    @Query("SELECT g FROM SuperAdminGrant g WHERE g.revokedAt IS NULL AND g.aliasExpiresAt > :now")
    fun findActiveTimeBounded(@Param("now") now: Instant): List<SuperAdminGrant>

    @Query("SELECT g FROM SuperAdminGrant g WHERE g.revokedAt IS NULL")
    fun findAllActive(): List<SuperAdminGrant>
}

@Repository
interface PricingGeoConfigRepository : JpaRepository<PricingGeoConfig, UUID> {
    fun findByTenantIdAndCityId(tenantId: String, cityId: String?): List<PricingGeoConfig>
}

@Repository
interface PricingGeoConfigHistoryRepository : JpaRepository<PricingGeoConfigHistory, UUID> {
    fun findByConfigIdOrderByOccurredAtDesc(configId: UUID): List<PricingGeoConfigHistory>
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