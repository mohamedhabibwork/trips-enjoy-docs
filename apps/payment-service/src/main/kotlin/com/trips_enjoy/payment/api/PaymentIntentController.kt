package com.trips_enjoy.payment.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.payment.application.IdempotencyService
import com.trips_enjoy.payment.application.PaymentIntentService
import com.trips_enjoy.payment.domain.PaymentIntent
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.util.UUID

/**
 * The public payment-intent REST controller. Implements
 * docs/services/payment-service/INTEGRATION.md §1:
 *   * POST /v1/payment-intents                    — §1.1
 *   * POST /v1/payment-intents/{id}/authorize     — §1.2
 *   * POST /v1/payment-intents/{id}/capture       — §1.3
 *   * POST /v1/payment-intents/{id}/void          — §1.4
 *   * POST /v1/payment-intents/{id}/refund        — §1.5
 *
 * Every mutating route requires:
 *   * Bearer JWT (Keycloak, realm platform-internal)
 *   * `payment.write` scope (enforced via @PreAuthorize)
 *   * `Idempotency-Key` header (canonical, format
 *     `request:{request_id}:payment:{action}`)
 */
@RestController
@RequestMapping("/v1/payment-intents")
class PaymentIntentController(
    private val paymentIntentService: PaymentIntentService,
    private val idemService: IdempotencyService,
    private val objectMapper: ObjectMapper,
) {

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_payment.write') or hasAuthority('SCOPE_payment.admin')")
    fun create(
        @Valid @RequestBody req: CreatePaymentIntentRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): ResponseEntity<CreatePaymentIntentResponse> {
        val requestHash = sha256(objectMapper.writeValueAsString(req))
        val correlationId = req.correlationId?.let(UUID::fromString) ?: UUID.randomUUID()
        val actingUserId = UUID.fromString(actingUser)

        val intent = paymentIntentService.create(
            customerId = req.customerIdAsUuid(),
            requestId = req.requestIdAsUuid(),
            service = req.service,
            amountMinor = req.amountMinor,
            currency = req.currency,
            gatewayRegion = req.gatewayRegion,
            captureMode = req.captureMode,
            cityId = req.cityIdAsUuidOrNull(),
            description = req.description,
            metadata = req.metadata,
            correlationId = correlationId,
            pin = req.gatewayPin,
            tenantId = req.tenantId,
            method = req.method,
            createdBy = actingUserId,
            idempotencyKey = idempotencyKey,
            requestHash = requestHash,
        )

        val body = CreatePaymentIntentResponse(
            paymentIntentId = intent.id.toString(),
            state = intent.state,
            amountMinor = intent.amountMinor,
            currency = intent.currency,
            gatewayId = intent.gatewayId,
            gatewayIntentId = intent.gatewayIntentId,
            correlationId = intent.correlationId.toString(),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(body)
    }

    @PostMapping("/{id}/authorize")
    @PreAuthorize("hasAuthority('SCOPE_payment.write') or hasAuthority('SCOPE_payment.admin')")
    fun authorize(
        @PathVariable("id") id: String,
        @Valid @RequestBody req: AuthorizePaymentIntentRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): AuthorizePaymentIntentResponse {
        val requestHash = sha256(objectMapper.writeValueAsString(req))
        val intentId = UUID.fromString(id)
        val actingUserId = UUID.fromString(actingUser)
        val correlationId = UUID.randomUUID()

        val intent = paymentIntentService.authorize(
            intentId = intentId,
            gatewayToken = req.gatewayToken,
            correlationId = correlationId,
            idempotencyKey = idempotencyKey,
            requestHash = requestHash,
            createdBy = actingUserId,
        )
        return AuthorizePaymentIntentResponse(
            paymentIntentId = intent.id.toString(),
            state = intent.state,
            amountMinor = intent.amountMinor,
            currency = intent.currency,
            authorizedAt = intent.authorizedAt?.toString() ?: "",
        )
    }

    @PostMapping("/{id}/capture")
    @PreAuthorize("hasAuthority('SCOPE_payment.write') or hasAuthority('SCOPE_payment.admin')")
    fun capture(
        @PathVariable("id") id: String,
        @Valid @RequestBody req: CapturePaymentIntentRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): CapturePaymentIntentResponse {
        val requestHash = sha256(objectMapper.writeValueAsString(req))
        val intentId = UUID.fromString(id)
        val actingUserId = UUID.fromString(actingUser)
        val correlationId = UUID.randomUUID()

        val intent = paymentIntentService.capture(
            intentId = intentId,
            amountMinor = req.amountMinor,
            correlationId = correlationId,
            idempotencyKey = idempotencyKey,
            requestHash = requestHash,
            createdBy = actingUserId,
        )
        return CapturePaymentIntentResponse(
            paymentIntentId = intent.id.toString(),
            state = intent.state,
            capturedMinor = intent.capturedMinor ?: 0L,
            currency = intent.currency,
            capturedAt = intent.capturedAt?.toString() ?: "",
        )
    }

    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('SCOPE_payment.write') or hasAuthority('SCOPE_payment.admin')")
    fun voidIntent(
        @PathVariable("id") id: String,
        @Valid @RequestBody req: VoidPaymentIntentRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): ResponseEntity<Map<String, Any?>> {
        val requestHash = sha256(objectMapper.writeValueAsString(req))
        val intentId = UUID.fromString(id)
        val actingUserId = UUID.fromString(actingUser)
        val correlationId = UUID.randomUUID()

        val intent = paymentIntentService.void(
            intentId = intentId,
            reason = req.reason,
            correlationId = correlationId,
            idempotencyKey = idempotencyKey,
            requestHash = requestHash,
            createdBy = actingUserId,
        )
        return ResponseEntity.ok(mapOf(
            "payment_intent_id" to intent.id.toString(),
            "state" to intent.state,
            "voided_at" to (intent.voidedAt?.toString() ?: ""),
        ))
    }

    @PostMapping("/{id}/refund")
    @PreAuthorize("hasAuthority('SCOPE_payment.write') or hasAuthority('SCOPE_payment.admin')")
    fun refund(
        @PathVariable("id") id: String,
        @Valid @RequestBody req: RefundPaymentIntentRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): ResponseEntity<Map<String, Any?>> {
        val requestHash = sha256(objectMapper.writeValueAsString(req))
        val intentId = UUID.fromString(id)
        val actingUserId = UUID.fromString(actingUser)
        val correlationId = UUID.randomUUID()

        val intent = paymentIntentService.refund(
            intentId = intentId,
            refundAmountMinor = req.refundAmountMinor,
            reason = req.reason,
            correlationId = correlationId,
            idempotencyKey = idempotencyKey,
            requestHash = requestHash,
            createdBy = actingUserId,
        )
        return ResponseEntity.ok(mapOf(
            "payment_intent_id" to intent.id.toString(),
            "state" to intent.state,
            "refunded_minor" to intent.refundedMinor,
        ))
    }

    private fun sha256(payload: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(payload.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}