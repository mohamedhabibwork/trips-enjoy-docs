package com.trips_enjoy.payment.api

import com.trips_enjoy.payment.domain.GatewayOverride
import com.trips_enjoy.payment.domain.GatewayOverrideRepository
import com.trips_enjoy.payment.domain.PaymentGateway
import com.trips_enjoy.payment.domain.PaymentGatewayRepository
import com.trips_enjoy.payment.gateway.GatewayRegistry
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * Admin endpoints for payment-service. Per
 * docs/services/payment-service/INTEGRATION.md §3 (admin):
 *   * GET /admin/v1/payments/gateways                        — list all 46
 *   * GET /admin/v1/payments/gateway-overrides              — list overrides
 *   * POST /admin/v1/payments/gateway-overrides             — create
 *   * POST /admin/v1/payments/gateway-overrides/{id}/enable — enable
 *   * POST /admin/v1/payments/gateway-overrides/{id}/disable — disable
 *   * DELETE /admin/v1/payments/gateway-overrides/{id}       — delete
 *   * POST /admin/v1/payments/gateways/{id}/health          — force health probe
 *
 * All admin endpoints require the `payment.admin` authority and a
 * break-glass co-signature header per SECURITY_ARCHITECTURE.md §14
 * (co-signature verification is implemented in the platform-spring-boot-starter).
 */
@RestController
@RequestMapping("/admin/v1/payments")
class AdminController(
    private val gatewayRepository: PaymentGatewayRepository,
    private val overrideRepository: GatewayOverrideRepository,
    private val gatewayRegistry: GatewayRegistry,
) {

    @GetMapping("/gateways")
    @PreAuthorize("hasAuthority('SCOPE_payment.admin')")
    fun listGateways(): List<Map<String, Any?>> =
        gatewayRepository.findAll().map { gw ->
            mapOf(
                "id" to gw.id,
                "kind" to gw.kind,
                "display_name" to gw.displayName,
                "state" to gw.state,
                "priority" to gw.priority,
                "regions" to gw.regions.toList(),
                "supported_currencies" to gw.supportedCurrencies.toList(),
                "supported_methods" to gw.supportedMethods.toList(),
                "health" to gw.health,
                "is_default" to gw.isDefault,
            )
        }

    @GetMapping("/gateway-overrides")
    @PreAuthorize("hasAuthority('SCOPE_payment.admin')")
    fun listOverrides(): List<Map<String, Any?>> =
        overrideRepository.findAll().filter { it.deletedAt == null }.map { o ->
            mapOf(
                "id" to o.id.toString(),
                "scope" to o.scope,
                "scope_key" to o.scopeKey,
                "gateway_id" to o.gatewayId,
                "priority" to o.priority,
                "enabled" to o.enabled,
                "notes" to o.notes,
                "updated_at" to o.updatedAt.toString(),
            )
        }

    @PostMapping("/gateway-overrides")
    @PreAuthorize("hasAuthority('SCOPE_payment.admin')")
    @Transactional
    fun createOverride(
        @Valid @RequestBody req: CreateGatewayOverrideRequest,
        @RequestHeader("X-User-Id") actingUser: String,
        @RequestHeader("X-Audit-Reason") auditReason: String,
    ): ResponseEntity<Map<String, Any?>> {
        val actingUserId = UUID.fromString(actingUser)
        val o = GatewayOverride(
            id = UUID.randomUUID(),
            scope = req.scope,
            scopeKey = req.scopeKey,
            gatewayId = req.gatewayId,
            priority = req.priority,
            notes = "$auditReason: ${req.notes ?: ""}",
            createdBy = actingUserId,
            updatedBy = actingUserId,
        )
        overrideRepository.save(o)
        return ResponseEntity.ok(mapOf(
            "id" to o.id.toString(),
            "scope" to o.scope,
            "scope_key" to o.scopeKey,
            "gateway_id" to o.gatewayId,
        ))
    }

    @PostMapping("/gateway-overrides/{id}/disable")
    @PreAuthorize("hasAuthority('SCOPE_payment.admin')")
    @Transactional
    fun disableOverride(
        @PathVariable("id") id: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): ResponseEntity<Void> {
        val o = overrideRepository.findById(UUID.fromString(id)).orElseThrow()
        o.disable(Instant.now())
        o.updatedBy = UUID.fromString(actingUser)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/gateway-overrides/{id}/enable")
    @PreAuthorize("hasAuthority('SCOPE_payment.admin')")
    @Transactional
    fun enableOverride(
        @PathVariable("id") id: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): ResponseEntity<Void> {
        val o = overrideRepository.findById(UUID.fromString(id)).orElseThrow()
        o.enable(Instant.now())
        o.updatedBy = UUID.fromString(actingUser)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/gateways/{id}/health")
    @PreAuthorize("hasAuthority('SCOPE_payment.admin')")
    @Transactional
    fun probeHealth(@PathVariable("id") id: String): ResponseEntity<Map<String, Any?>> {
        val gw = gatewayRepository.findById(id).orElseThrow()
        val driver = gatewayRegistry.driverFor(id)
        val health = driver.health()
        gw.health = when (health) {
            com.trips_enjoy.payment.gateway.GatewayHealth.HEALTHY -> PaymentGateway.HEALTH_HEALTHY
            com.trips_enjoy.payment.gateway.GatewayHealth.DEGRADED -> PaymentGateway.HEALTH_DEGRADED
            com.trips_enjoy.payment.gateway.GatewayHealth.UNREACHABLE -> PaymentGateway.HEALTH_UNREACHABLE
        }
        gw.healthLastCheckedAt = Instant.now()
        return ResponseEntity.ok(mapOf(
            "id" to id,
            "health" to gw.health,
            "checked_at" to gw.healthLastCheckedAt.toString(),
        ))
    }
}

data class CreateGatewayOverrideRequest(
    @field:NotBlank @field:Pattern(regexp = "^(tenant|region|currency|method|tenant_region|tenant_currency)$")
    val scope: String,
    @field:NotBlank val scopeKey: String,
    @field:NotBlank val gatewayId: String,
    @field:Min(0) val priority: Int = 100,
    val notes: String? = null,
)