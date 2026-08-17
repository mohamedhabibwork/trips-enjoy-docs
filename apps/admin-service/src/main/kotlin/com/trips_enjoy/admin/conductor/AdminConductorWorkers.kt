package com.trips_enjoy.admin.conductor

import com.trips_enjoy.admin.application.AdminWriteService
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The Conductor workflow workers for admin-service. Per ADR-0018 +
 * shared/CONDUCTOR_WORKFLOWS.md, admin-service owns 3 of the 17
 * workflow IDs:
 *   - wf.admin.super_admin_grant.v1
 *   - wf.admin.super_admin_revoke.v1
 *   - wf.admin.geo_config_upsert.v1
 *
 * Each worker is a thin wrapper that translates a Conductor task input
 * map to a call into the AdminWriteService application layer.
 */
@Component
class AdminConductorWorkers(
    private val adminWriteService: AdminWriteService,
) {

    /**
     * Conductor task: admin.super_admin_grant — grants the canonical
     * SUPER_ADMIN preset (1 × platform.super_admin + 20 × <service>.admin)
     * to the grantee, with optional break-glass co-signature.
     */
    fun superAdminGrant(input: Map<String, Any?>): Map<String, Any?> {
        val grant = adminWriteService.grantSuperAdmin(
            granteeKcSub = UUID.fromString(input["grantee_kc_sub"] as String),
            granteeEmail = input["grantee_email"] as? String,
            grantedByKcSub = UUID.fromString(input["acting_user_id"] as String),
            grantedByEmail = input["granted_by_email"] as? String,
            reason = input["reason"] as String,
            aliasKind = input["alias_kind"] as? String ?: "permanent",
            aliasExpiresAt = (input["alias_expires_at"] as? String)?.let(java.time.Instant::parse),
            correlationId = UUID.fromString(input["correlation_id"] as String),
            createdBy = UUID.fromString(input["acting_user_id"] as String),
            idempotencyKey = input["idempotency_key"] as String,
            requestHash = (input["request_hash"] as? String) ?: sha256(""),
        )
        return mapOf(
            "grant_id" to grant.id.toString(),
            "grantee_kc_sub" to grant.granteeKcSub.toString(),
            "alias_kind" to grant.aliasKind,
        )
    }

    /**
     * Conductor task: admin.super_admin_revoke — revokes an existing
     * SUPER_ADMIN grant.
     */
    fun superAdminRevoke(input: Map<String, Any?>): Map<String, Any?> {
        val grant = adminWriteService.revokeSuperAdmin(
            grantId = UUID.fromString(input["grant_id"] as String),
            revokedByKcSub = UUID.fromString(input["acting_user_id"] as String),
            correlationId = UUID.fromString(input["correlation_id"] as String),
            createdBy = UUID.fromString(input["acting_user_id"] as String),
            idempotencyKey = input["idempotency_key"] as String,
            requestHash = (input["request_hash"] as? String) ?: sha256(""),
        )
        return mapOf(
            "grant_id" to grant.id.toString(),
            "revoked_at" to grant.revokedAt?.toString(),
        )
    }

    /**
     * Conductor task: admin.geo_config_upsert — upserts a pricing
     * geo-config rule and emits `pricing.geo_config.updated.v1`.
     */
    fun geoConfigUpsert(input: Map<String, Any?>): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val value = (input["value"] as? Map<String, Any?>) ?: emptyMap()
        val cfg = adminWriteService.upsertPricingGeoConfig(
            tenantId = input["tenant_id"] as String,
            cityId = input["city_id"] as? String,
            originZoneId = (input["origin_zone_id"] as? String)?.let(UUID::fromString),
            destinationZoneId = (input["destination_zone_id"] as? String)?.let(UUID::fromString),
            rideType = input["ride_type"] as? String,
            ruleKind = input["rule_kind"] as String,
            value = value,
            priority = (input["priority"] as? Number)?.toInt() ?: 100,
            effectiveFrom = (input["effective_from"] as? String)?.let(java.time.Instant::parse),
            effectiveTo = (input["effective_to"] as? String)?.let(java.time.Instant::parse),
            createdByKcSub = UUID.fromString(input["acting_user_id"] as String),
            correlationId = UUID.fromString(input["correlation_id"] as String),
            idempotencyKey = input["idempotency_key"] as String,
            requestHash = (input["request_hash"] as? String) ?: sha256(""),
        )
        return mapOf("config_id" to cfg.id.toString())
    }

    private fun sha256(payload: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(payload.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}