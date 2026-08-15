package com.trips_enjoy.payment.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * The 46-gateway registry entry — one row per supported payment gateway.
 *
 * Mirrors `payment.payment_gateways` per docs/services/payment-service/ERD.md §3.
 * Mirrors the `storage_drivers` table in file-service (lifted pattern). Seeded
 * from `configuration-service.payment.gateway.<id>.*` family on
 * `configuration.updated.v1`. `is_default` is the env-level default picked
 * when no more-specific override resolves.
 *
 * The 46 supported gateway ids are enumerated in
 * docs/services/payment-service/GATEWAYS.md (e.g. `stripe`, `paypal`,
 * `paymob`, `binance`, `perfect_money`, `volet`, `payeer`, `now_payments`,
 * `adyen`, `checkout_com`, `razorpay`, `paystack`, ...).
 */
@Entity
@Table(name = "payment_gateways", schema = "payment")
class PaymentGateway(
    @Id val id: String,
    @Column(nullable = false) var kind: String,
    @Column(name = "display_name", nullable = false) var displayName: String,
    @Column(nullable = false) var state: String = STATE_ENABLED,
    @Column(nullable = false) var priority: Int = 100,
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "regions", nullable = false, columnDefinition = "text[]")
    var regions: Array<String> = emptyArray(),
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "supported_currencies", nullable = false, columnDefinition = "text[]")
    var supportedCurrencies: Array<String> = emptyArray(),
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "supported_methods", nullable = false, columnDefinition = "text[]")
    var supportedMethods: Array<String> = emptyArray(),
    @Column(name = "signature_scheme", nullable = false) var signatureScheme: String,
    @Column(name = "verify_style", nullable = false) var verifyStyle: String,
    @Column(name = "vault_path", nullable = false) var vaultPath: String,
    @Column(name = "health_url") var healthUrl: String? = null,
    @Column(nullable = false) var health: String = HEALTH_HEALTHY,
    @Column(name = "health_last_checked_at") var healthLastCheckedAt: Instant? = null,
    @Column(name = "is_default", nullable = false) var isDefault: Boolean = false,
    @Column(name = "config_hash", nullable = false) var configHash: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb") var metadata: Map<String, Any?>? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
    @Column(name = "created_by", nullable = false) val createdBy: UUID,
    @Column(name = "updated_by", nullable = false) var updatedBy: UUID,
    @Column(name = "version", nullable = false) var version: Int = 1,
    @Column(name = "deleted_at") var deletedAt: Instant? = null,
) {
    companion object {
        const val STATE_ENABLED = "enabled"
        const val STATE_DRAINING = "draining"
        const val STATE_DISABLED = "disabled"

        const val HEALTH_HEALTHY = "healthy"
        const val HEALTH_DEGRADED = "degraded"
        const val HEALTH_UNREACHABLE = "unreachable"

        val VALID_KINDS: Set<String> = setOf(
            "card", "mena_wallet", "mena_aggregator", "crypto",
            "e_currency", "direct_card_3ds", "payout", "latam",
            "apac", "local_apm"
        )
        val VALID_METHODS: Set<String> = setOf(
            "card", "wallet", "bnpl", "bank_transfer", "crypto"
        )
    }

    fun isAcceptable(currency: String, region: String, method: String): Boolean {
        if (state != STATE_ENABLED) return false
        if (currency !in supportedCurrencies) return false
        if (region !in regions) return false
        if (method !in supportedMethods) return false
        return true
    }
}