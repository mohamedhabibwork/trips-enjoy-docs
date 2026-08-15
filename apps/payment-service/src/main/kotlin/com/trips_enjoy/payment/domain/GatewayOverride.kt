package com.trips_enjoy.payment.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Per-tenant / per-region / per-currency / per-method gateway override.
 * Mirrors `payment.gateway_overrides` per docs/services/payment-service/ERD.md §3.
 * Written by admin-service via POST /v1/admin/payments/gateway-overrides.
 * Read by the GatewayRegistry to resolve a gateway per payment intent
 * (per GATEWAYS.md §6 precedence: pin → tenant → region → currency →
 * method → env_default → auto).
 */
@Entity
@Table(name = "gateway_overrides", schema = "payment")
class GatewayOverride(
    @Id val id: UUID,
    @Column(nullable = false) val scope: String,
    @Column(name = "scope_key", nullable = false) val scopeKey: String,
    @Column(name = "gateway_id", nullable = false) var gatewayId: String,
    @Column(nullable = false) var priority: Int = 100,
    @Column(nullable = false) var enabled: Boolean = true,
    @Column var notes: String? = null,
    @Column(name = "created_by", nullable = false) val createdBy: UUID,
    @Column(name = "updated_by", nullable = false) var updatedBy: UUID,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
    @Column(name = "deleted_at") var deletedAt: Instant? = null,
) {
    companion object {
        const val SCOPE_TENANT = "tenant"
        const val SCOPE_REGION = "region"
        const val SCOPE_CURRENCY = "currency"
        const val SCOPE_METHOD = "method"
        const val SCOPE_TENANT_REGION = "tenant_region"
        const val SCOPE_TENANT_CURRENCY = "tenant_currency"

        val VALID_SCOPES: Set<String> = setOf(
            SCOPE_TENANT, SCOPE_REGION, SCOPE_CURRENCY,
            SCOPE_METHOD, SCOPE_TENANT_REGION, SCOPE_TENANT_CURRENCY
        )
    }

    init {
        require(scope in VALID_SCOPES) { "unknown scope $scope" }
        require(scopeKey.isNotBlank()) { "scope_key required" }
    }

    fun disable(at: Instant) {
        enabled = false
        updatedAt = at
    }

    fun enable(at: Instant) {
        enabled = true
        updatedAt = at
    }
}