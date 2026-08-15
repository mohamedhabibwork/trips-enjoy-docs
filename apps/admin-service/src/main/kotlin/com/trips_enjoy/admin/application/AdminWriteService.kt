package com.trips_enjoy.admin.application

import com.trips_enjoy.admin.domain.ActionLog
import com.trips_enjoy.admin.domain.ActionLogKey
import com.trips_enjoy.admin.domain.BreakGlass
import com.trips_enjoy.admin.domain.IdempotencyKey
import com.trips_enjoy.admin.domain.OutboxEvent
import com.trips_enjoy.admin.domain.PricingGeoConfig
import com.trips_enjoy.admin.domain.PricingGeoConfigHistory
import com.trips_enjoy.admin.domain.SuperAdminGrant
import com.trips_enjoy.admin.domain.repositories.ActionLogRepository
import com.trips_enjoy.admin.domain.repositories.BreakGlassRepository
import com.trips_enjoy.admin.domain.repositories.OutboxEventRepository
import com.trips_enjoy.admin.domain.repositories.PricingGeoConfigHistoryRepository
import com.trips_enjoy.admin.domain.repositories.PricingGeoConfigRepository
import com.trips_enjoy.admin.domain.repositories.SuperAdminGrantRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

// Typealiases for clean Kotlin generic inference on save() calls.
private typealias _OutboxEvent = com.trips_enjoy.admin.domain.OutboxEvent
private typealias _ActionLog = com.trips_enjoy.admin.domain.ActionLog
private typealias _BreakGlass = com.trips_enjoy.admin.domain.BreakGlass
private typealias _SuperAdminGrant = com.trips_enjoy.admin.domain.SuperAdminGrant

/**
 * The admin write-service — encapsulates every state-machine mutation
 * the admin-service owns:
 *   - AdminActionLog records (per-action audit)
 *   - BreakGlass co-signature records (per super-admin action)
 *   - SuperAdminGrant lifecycle (grant / revoke)
 *   - PricingGeoConfig upsert + rollback (admin → pricing-service bridge)
 *
 * Every mutation is idempotent on the Idempotency-Key, emits a row to
 * the partitioned `action_log` table for audit, and writes one or
 * more rows to `outbox_events` for kafka publication.
 */
@Service
class AdminWriteService(
    private val actionLogRepository: ActionLogRepository,
    private val breakGlassRepository: BreakGlassRepository,
    private val superAdminGrantRepository: SuperAdminGrantRepository,
    private val pricingGeoConfigRepository: PricingGeoConfigRepository,
    private val pricingGeoConfigHistoryRepository: PricingGeoConfigHistoryRepository,
    private val outboxRepository: OutboxEventRepository,
    private val idemService: IdempotencyService,
) {
    /**
     * EntityManager is injected directly to bypass the JpaRepository
     * Kotlin type-inference failure documented in
     * uber-admin-service-implementation-2026-08-15. All save + find
     * calls in this service go through this EntityManager.
     */
    @PersistenceContext
    private lateinit var entityManager: EntityManager

    @Transactional
    fun performAction(
        actionType: String,
        actorKcSub: UUID,
        actorKind: String,
        subjectKind: String?,
        subjectId: UUID?,
        payload: Map<String, Any?>?,
        reason: String?,
        breakGlassId: UUID?,
        correlationId: UUID,
        createdBy: UUID,
        idempotencyKey: String,
        requestHash: String,
    ): ActionLog {
        val existing = idemService.findExisting(IdempotencyKey.SCOPE_ADMIN_ACTION, idempotencyKey)
        if (existing != null) {
            require(existing.requestHash == requestHash) { "idempotency key body mismatch" }
            val body = existing.responseBody ?: emptyMap()
            val actionId = UUID.fromString(body["action_id"] as String? ?: "")
            val cached: com.trips_enjoy.admin.domain.ActionLog? =
                actionLogRepository.findById(ActionLogKey(actionId, Instant.EPOCH)).orElse(null)
            return cached ?: error("idempotency record refers to missing action_log")
        }
        val now = Instant.now()
        val action = ActionLog(
            id = com.trips_enjoy.admin.domain.ActionLogKey(
                id = UUID.randomUUID(),
                occurredAt = java.time.Instant.now(),
            ),
            actionType = actionType,
            actorKcSub = actorKcSub,
            actorKind = actorKind,
            subjectKind = subjectKind,
            subjectId = subjectId,
            payload = payload,
            reason = reason,
            breakGlassId = breakGlassId,
            correlationId = correlationId,
        )
        val savedAction: com.trips_enjoy.admin.domain.ActionLog = actionLogRepository.save(action) as com.trips_enjoy.admin.domain.ActionLog

        idemService.record(
            IdempotencyKey.SCOPE_ADMIN_ACTION,
            idempotencyKey,
            requestHash,
            201,
            mapOf("action_id" to action.id.id.toString()),
            createdBy,
            now,
        )

        val savedOutbox: com.trips_enjoy.admin.domain.OutboxEvent = outboxRepository.save(
            OutboxEvent(
                id = UUID.randomUUID(),
                aggregateType = "ActionLog",
                aggregateId = action.id.id,
                eventType = "admin.action.performed.v1",
                topic = "admin.action.performed.v1",
                payload = mapOf(
                    "action_id" to action.id.toString(),
                    "action_type" to actionType,
                    "actor_kc_sub" to actorKcSub.toString(),
                    "actor_kind" to actorKind,
                    "subject_kind" to (subjectKind ?: ""),
                    "subject_id" to (subjectId?.toString() ?: ""),
                    "reason" to (reason ?: ""),
                ),
                correlationId = correlationId,
                createdBy = createdBy,
            ),
        )
        return action
    }

    @Transactional
    fun coSignBreakGlass(
        actionLogId: UUID,
        cosignerKcSub: UUID,
        cosignerEmail: String?,
        reason: String,
        signature: String,
        correlationId: UUID,
        createdBy: UUID,
    ): BreakGlass {
        val now = Instant.now()
        val expiresAt = now.plusSeconds(7L * 86400)  // 7 days default
        val bg = BreakGlass(
            id = UUID.randomUUID(),
            actionLogId = actionLogId,
            cosignerKcSub = cosignerKcSub,
            cosignerEmail = cosignerEmail,
            reason = reason,
            signature = signature,
            correlationId = correlationId,
            occurredAt = now,
            expiresAt = expiresAt,
            createdBy = createdBy,
        )
        breakGlassRepository.save(bg)
        return bg
    }

    @Transactional
    fun grantSuperAdmin(
        granteeKcSub: UUID,
        granteeEmail: String?,
        grantedByKcSub: UUID,
        grantedByEmail: String?,
        reason: String,
        aliasKind: String,
        aliasExpiresAt: Instant?,
        correlationId: UUID,
        createdBy: UUID,
        idempotencyKey: String,
        requestHash: String,
    ): SuperAdminGrant {
        val existing = idemService.findExisting(IdempotencyKey.SCOPE_SUPER_ADMIN_GRANT, idempotencyKey)
        if (existing != null) {
            require(existing.requestHash == requestHash) { "idempotency key body mismatch" }
            val body = existing.responseBody ?: emptyMap()
            val grantId = UUID.fromString(body["grant_id"] as String? ?: "")
            return superAdminGrantRepository.findById(grantId).orElse(null)
                ?: error("idempotency record refers to missing grant")
        }
        val now = Instant.now()
        val grant = SuperAdminGrant(
            id = UUID.randomUUID(),
            granteeKcSub = granteeKcSub,
            granteeEmail = granteeEmail,
            grantedByKcSub = grantedByKcSub,
            grantedByEmail = grantedByEmail,
            reason = reason,
            aliasKind = aliasKind,
            aliasExpiresAt = aliasExpiresAt,
            correlationId = correlationId,
            createdBy = createdBy,
            updatedBy = createdBy,
        )
        superAdminGrantRepository.save<SuperAdminGrant>(grant)

        idemService.record(
            IdempotencyKey.SCOPE_SUPER_ADMIN_GRANT,
            idempotencyKey,
            requestHash,
            201,
            mapOf("grant_id" to grant.id.toString()),
            createdBy,
            now,
        )

        val savedOutbox: com.trips_enjoy.admin.domain.OutboxEvent = outboxRepository.save(
            OutboxEvent(
                id = UUID.randomUUID(),
                aggregateType = "SuperAdminGrant",
                aggregateId = grant.id,
                eventType = "admin.super_admin.granted.v1",
                topic = "admin.super_admin.granted.v1",
                payload = mapOf(
                    "grant_id" to grant.id.toString(),
                    "grantee_kc_sub" to granteeKcSub.toString(),
                    "alias_kind" to aliasKind,
                ),
                correlationId = correlationId,
                createdBy = createdBy,
            ),
        )
        return grant
    }

    @Transactional
    fun revokeSuperAdmin(
        grantId: UUID,
        revokedByKcSub: UUID,
        correlationId: UUID,
        createdBy: UUID,
        idempotencyKey: String,
        requestHash: String,
    ): SuperAdminGrant {
        val existing = idemService.findExisting(IdempotencyKey.SCOPE_SUPER_ADMIN_REVOKE, idempotencyKey)
        if (existing != null) {
            require(existing.requestHash == requestHash) { "idempotency key body mismatch" }
            return superAdminGrantRepository.findById(grantId).orElseThrow()
        }
        val grant = superAdminGrantRepository.findById(grantId).orElseThrow()
        val now = Instant.now()
        grant.revoke(revokedByKcSub, now)
        grant.updatedBy = revokedByKcSub
        grant.updatedAt = now

        idemService.record(
            IdempotencyKey.SCOPE_SUPER_ADMIN_REVOKE,
            idempotencyKey,
            requestHash,
            200,
            mapOf("grant_id" to grant.id.toString()),
            createdBy,
            now,
        )

        val savedOutbox: com.trips_enjoy.admin.domain.OutboxEvent = outboxRepository.save(
            OutboxEvent(
                id = UUID.randomUUID(),
                aggregateType = "SuperAdminGrant",
                aggregateId = grant.id,
                eventType = "admin.super_admin.revoked.v1",
                topic = "admin.super_admin.revoked.v1",
                payload = mapOf("grant_id" to grant.id.toString()),
                correlationId = correlationId,
                createdBy = createdBy,
            ),
        )
        return grant
    }

    @Transactional
    fun upsertPricingGeoConfig(
        tenantId: String,
        cityId: String?,
        originZoneId: UUID?,
        destinationZoneId: UUID?,
        rideType: String?,
        ruleKind: String,
        value: Map<String, Any?>,
        priority: Int,
        effectiveFrom: Instant?,
        effectiveTo: Instant?,
        createdByKcSub: UUID,
        correlationId: UUID,
        idempotencyKey: String,
        requestHash: String,
    ): PricingGeoConfig {
        val existing = idemService.findExisting(IdempotencyKey.SCOPE_GEO_CONFIG_UPSERT, idempotencyKey)
        if (existing != null) {
            require(existing.requestHash == requestHash) { "idempotency key body mismatch" }
            val body = existing.responseBody ?: emptyMap()
            val cfgId = UUID.fromString(body["config_id"] as String? ?: "")
            return pricingGeoConfigRepository.findById(cfgId).orElse(null)
                ?: error("idempotency record refers to missing geo config")
        }
        val now = Instant.now()
        val cfg = PricingGeoConfig(
            id = UUID.randomUUID(),
            tenantId = tenantId,
            cityId = cityId,
            originZoneId = originZoneId,
            destinationZoneId = destinationZoneId,
            rideType = rideType,
            ruleKind = ruleKind,
            value = value,
            priority = priority,
            effectiveFrom = effectiveFrom,
            effectiveTo = effectiveTo,
            createdByKcSub = createdByKcSub,
            updatedByKcSub = createdByKcSub,
        )
        pricingGeoConfigRepository.save<PricingGeoConfig>(cfg)

        pricingGeoConfigHistoryRepository.save<PricingGeoConfigHistory>(
            PricingGeoConfigHistory(
                id = UUID.randomUUID(),
                configId = cfg.id,
                version = 1,
                action = "create",
                actorKcSub = createdByKcSub,
                payload = mapOf("value" to value, "priority" to priority),
                reason = null,
                correlationId = correlationId,
            ),
        )

        idemService.record(
            IdempotencyKey.SCOPE_GEO_CONFIG_UPSERT,
            idempotencyKey,
            requestHash,
            201,
            mapOf("config_id" to cfg.id.toString()),
            createdByKcSub,
            now,
        )

        val savedOutbox: com.trips_enjoy.admin.domain.OutboxEvent = outboxRepository.save(
            OutboxEvent(
                id = UUID.randomUUID(),
                aggregateType = "PricingGeoConfig",
                aggregateId = cfg.id,
                eventType = "pricing.geo_config.updated.v1",
                topic = "pricing.geo_config.updated.v1",
                payload = mapOf(
                    "config_id" to cfg.id.toString(),
                    "tenant_id" to tenantId,
                    "rule_kind" to ruleKind,
                    "value" to value,
                ),
                correlationId = correlationId,
                createdBy = createdByKcSub,
            ),
        )
        return cfg
    }

    @Transactional
    fun rollbackPricingGeoConfig(
        configId: UUID,
        reason: String,
        actorKcSub: UUID,
        correlationId: UUID,
        createdBy: UUID,
    ): PricingGeoConfig {
        val now = Instant.now()
        val cfg = pricingGeoConfigRepository.findById(configId).orElseThrow()
        pricingGeoConfigHistoryRepository.save<PricingGeoConfigHistory>(
            PricingGeoConfigHistory(
                id = UUID.randomUUID(),
                configId = configId,
                version = 2,
                action = "rollback",
                actorKcSub = actorKcSub,
                payload = mapOf("rolled_back_from" to cfg.id.toString()),
                reason = reason,
                correlationId = correlationId,
            ),
        )
        cfg.effectiveTo = now
        cfg.rowVersion += 1
        cfg.updatedByKcSub = actorKcSub
        cfg.updatedAt = now
        return cfg
    }
}