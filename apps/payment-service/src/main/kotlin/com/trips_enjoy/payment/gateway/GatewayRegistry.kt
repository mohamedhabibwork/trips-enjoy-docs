package com.trips_enjoy.payment.gateway

import com.trips_enjoy.payment.domain.GatewayOverride
import com.trips_enjoy.payment.domain.GatewayOverrideRepository
import com.trips_enjoy.payment.domain.PaymentGateway
import com.trips_enjoy.payment.domain.PaymentGatewayAssignment
import com.trips_enjoy.payment.domain.PaymentGatewayAssignmentRepository
import com.trips_enjoy.payment.domain.PaymentGatewayRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * The gateway registry — resolves a `PaymentGatewayDriver` for a payment
 * intent and records the resolution in `payment.payment_gateway_assignments`
 * for audit. Implements the precedence per GATEWAYS.md §6:
 *
 *   1. gateway_pin          (explicit per-intent pin by admin)
 *   2. tenant_override      (per-tenant default gateway)
 *   3. region_default       (per-region default gateway)
 *   4. currency_default     (per-currency default gateway)
 *   5. method_default       (per-method default gateway)
 *   6. env_default          (the `is_default` row)
 *   7. auto                 (lowest-priority enabled gateway whose
 *                            regions/currencies/methods match)
 *
 * The registry caches the 46 driver instances in memory at startup and
 * looks them up by id. Real production deployments reload the cache
 * when a `configuration.updated.v1` event arrives.
 */
@Component
class GatewayRegistry(
    private val gatewayRepository: PaymentGatewayRepository,
    private val overrideRepository: GatewayOverrideRepository,
    private val assignmentRepository: PaymentGatewayAssignmentRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val drivers: MutableMap<String, PaymentGatewayDriver> = ConcurrentHashMap()

    init {
        // Wire the 6 real drivers + 40 no-op fallbacks at startup.
        for (id in SupportedGateways.ALL_GATEWAY_IDS) {
            drivers[id] = SupportedGateways.driverFor(id)
        }
    }

    /**
     * Look up a driver by gateway id. Throws if no driver is registered
     * (which can only happen if the GATEWAYS.md list and the database
     * registry are out of sync).
     */
    fun driverFor(gatewayId: String): PaymentGatewayDriver =
        drivers[gatewayId] ?: error("no driver registered for gateway $gatewayId")

    /**
     * Resolve a gateway for a payment intent. Returns the chosen gateway
     * row + the rule that fired (for the audit row).
     */
    @Transactional
    fun resolve(
        pin: String?,
        tenantId: String?,
        region: String,
        currency: String,
        method: String,
        paymentIntentId: UUID,
        createdBy: UUID,
    ): ResolvedGateway {
        // 1. gateway_pin
        if (pin != null) {
            val gw = gatewayRepository.findById(pin).orElse(null)
            if (gw != null && gw.state == PaymentGateway.STATE_ENABLED) {
                recordAssignment(paymentIntentId, pin, "gateway_pin", null, createdBy)
                return ResolvedGateway(gw, "gateway_pin", null)
            }
        }
        // 2. tenant_override
        if (tenantId != null) {
            val matches = overrideRepository
                .findByScopeAndScopeKeyAndEnabledTrueAndDeletedAtIsNull(
                    GatewayOverride.SCOPE_TENANT, tenantId,
                )
            val chosen = matches.firstOrNull()?.gatewayId
            if (chosen != null) {
                val gw = gatewayRepository.findById(chosen).orElse(null)
                if (gw != null && gw.state == PaymentGateway.STATE_ENABLED) {
                    recordAssignment(paymentIntentId, chosen, "tenant_override", "tenant:$tenantId", createdBy)
                    return ResolvedGateway(gw, "tenant_override", "tenant:$tenantId")
                }
            }
        }
        // 3. region_default
        val regionMatch = overrideRepository
            .findByScopeAndScopeKeyAndEnabledTrueAndDeletedAtIsNull(
                GatewayOverride.SCOPE_REGION, region,
            ).firstOrNull()
        if (regionMatch != null) {
            val gw = gatewayRepository.findById(regionMatch.gatewayId).orElse(null)
            if (gw != null && gw.state == PaymentGateway.STATE_ENABLED) {
                recordAssignment(paymentIntentId, gw.id, "region_default", "region:$region", createdBy)
                return ResolvedGateway(gw, "region_default", "region:$region")
            }
        }
        // 4. currency_default
        val currencyMatch = overrideRepository
            .findByScopeAndScopeKeyAndEnabledTrueAndDeletedAtIsNull(
                GatewayOverride.SCOPE_CURRENCY, currency,
            ).firstOrNull()
        if (currencyMatch != null) {
            val gw = gatewayRepository.findById(currencyMatch.gatewayId).orElse(null)
            if (gw != null && gw.state == PaymentGateway.STATE_ENABLED) {
                recordAssignment(paymentIntentId, gw.id, "currency_default", "currency:$currency", createdBy)
                return ResolvedGateway(gw, "currency_default", "currency:$currency")
            }
        }
        // 5. method_default
        val methodMatch = overrideRepository
            .findByScopeAndScopeKeyAndEnabledTrueAndDeletedAtIsNull(
                GatewayOverride.SCOPE_METHOD, method,
            ).firstOrNull()
        if (methodMatch != null) {
            val gw = gatewayRepository.findById(methodMatch.gatewayId).orElse(null)
            if (gw != null && gw.state == PaymentGateway.STATE_ENABLED) {
                recordAssignment(paymentIntentId, gw.id, "method_default", "method:$method", createdBy)
                return ResolvedGateway(gw, "method_default", "method:$method")
            }
        }
        // 6. env_default
        val envDefault = gatewayRepository.findByIsDefaultTrue()
        if (envDefault != null && envDefault.state == PaymentGateway.STATE_ENABLED) {
            recordAssignment(paymentIntentId, envDefault.id, "env_default", null, createdBy)
            return ResolvedGateway(envDefault, "env_default", null)
        }
        // 7. auto — pick the lowest-priority enabled gateway whose
        // regions/currencies/methods match.
        val candidates = gatewayRepository.findByState(PaymentGateway.STATE_ENABLED)
            .filter { it.isAcceptable(currency, region, method) }
            .sortedBy { it.priority }
        val auto = candidates.firstOrNull()
            ?: error("no gateway can accept currency=$currency region=$region method=$method")
        recordAssignment(paymentIntentId, auto.id, "auto", null, createdBy)
        return ResolvedGateway(auto, "auto", null)
    }

    private fun recordAssignment(
        paymentIntentId: UUID,
        gatewayId: String,
        source: String,
        ruleId: String?,
        createdBy: UUID,
    ) {
        val assignment = PaymentGatewayAssignment(
            id = UUID.randomUUID(),
            paymentIntentId = paymentIntentId,
            gatewayId = gatewayId,
            source = source,
            ruleId = ruleId,
            effectiveAt = Instant.now(),
            createdBy = createdBy,
        )
        assignmentRepository.save(assignment)
    }
}

data class ResolvedGateway(
    val gateway: PaymentGateway,
    val source: String,
    val ruleId: String?,
)

/**
 * Tiny shim around a thread-safe HashMap so the registry can hold the
 * 46 drivers without pulling in `java.util.concurrent.ConcurrentHashMap`
 * at every call site.
 */
private typealias ConcurrentHashMap<K, V> = java.util.concurrent.ConcurrentHashMap<K, V>