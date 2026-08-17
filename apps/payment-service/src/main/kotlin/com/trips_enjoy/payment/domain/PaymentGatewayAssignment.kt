package com.trips_enjoy.payment.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Per-intent gateway-source audit. Records which rule fired when the
 * GatewayRegistry resolved a gateway for a payment intent. Mirrors
 * `payment.payment_gateway_assignments` per docs/services/payment-service/ERD.md §3.
 * Sources: gateway_pin / tenant_override / region_default /
 * currency_default / method_default / env_default / auto.
 */
@Entity
@Table(name = "payment_gateway_assignments", schema = "payment")
class PaymentGatewayAssignment(
    @Id val id: UUID,
    @Column(name = "payment_intent_id", nullable = false) val paymentIntentId: UUID,
    @Column(name = "gateway_id", nullable = false) val gatewayId: String,
    @Column(nullable = false) val source: String,
    @Column(name = "rule_id") val ruleId: String? = null,
    @Column(name = "effective_at", nullable = false) val effectiveAt: Instant = Instant.now(),
    @Column(name = "created_by", nullable = false) val createdBy: UUID,
) {
    companion object {
        const val SOURCE_GATEWAY_PIN = "gateway_pin"
        const val SOURCE_TENANT_OVERRIDE = "tenant_override"
        const val SOURCE_REGION_DEFAULT = "region_default"
        const val SOURCE_CURRENCY_DEFAULT = "currency_default"
        const val SOURCE_METHOD_DEFAULT = "method_default"
        const val SOURCE_ENV_DEFAULT = "env_default"
        const val SOURCE_AUTO = "auto"

        val VALID_SOURCES: Set<String> = setOf(
            SOURCE_GATEWAY_PIN, SOURCE_TENANT_OVERRIDE, SOURCE_REGION_DEFAULT,
            SOURCE_CURRENCY_DEFAULT, SOURCE_METHOD_DEFAULT, SOURCE_ENV_DEFAULT,
            SOURCE_AUTO
        )
    }

    init {
        require(source in VALID_SOURCES) { "unknown source $source" }
    }
}