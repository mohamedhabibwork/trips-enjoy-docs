package com.trips_enjoy.customer.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.customer.application.CustomerKycHistoryApplicationService
import com.trips_enjoy.customer.application.CustomerReadService
import com.trips_enjoy.customer.application.CustomerWriteService
import com.trips_enjoy.customer.application.IdempotencyService
import com.trips_enjoy.customer.application.KycService
import com.trips_enjoy.customer.util.CorrelationContext
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Public HTTP API for customer-service (INTEGRATION.md §1).
 *
 * Every endpoint requires a valid JWT (SecurityConfiguration). The
 * per-endpoint authority requirement is enforced via @PreAuthorize.
 * Self-service endpoints additionally check `X-User-Id == customer_id`
 * via the controller's helper.
 *
 * Snake-case JSON keys throughout (CONVENTIONS.md §7).
 */
@RestController
@RequestMapping("/v1/customers")
class CustomerController(
    private val readService: CustomerReadService,
    private val writeService: CustomerWriteService,
    private val kycService: KycService,
    private val kycHistoryService: CustomerKycHistoryApplicationService,
    private val idempotencyService: IdempotencyService,
    private val mapper: ObjectMapper,
) {
    // -----------------------------------------------------------------------
    // 1.1 GET /v1/customers/{customer_id}
    // -----------------------------------------------------------------------
    @GetMapping("/{customer_id}")
    @PreAuthorize(
        "hasAnyAuthority('ROLE_customer.read', 'ROLE_customer.admin', " +
            "'ROLE_customer.read.any', 'ROLE_platform.admin', 'ROLE_platform.super_admin')",
    )
    fun get(
        @PathVariable("customer_id") customerId: UUID,
        authentication: Authentication,
        httpRequest: HttpServletRequest,
    ): CustomerResponse {
        val customer = readService.get(customerId)
        authorizeSelfOrAny(customer.identityId, authentication)
        return customer.toResponse()
    }

    // -----------------------------------------------------------------------
    // 1.2 POST /v1/customers
    // -----------------------------------------------------------------------
    @PostMapping
    @PreAuthorize(
        "hasAnyAuthority('ROLE_customer.write', 'ROLE_platform.admin', 'ROLE_platform.super_admin')",
    )
    fun create(
        @Valid @RequestBody body: CreateCustomerRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: UUID,
        authentication: Authentication,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<CustomerResponse> {
        val correlationId = CorrelationContext.correlationId(httpRequest)
        val actorId = CorrelationContext.actorId(authentication)
        val cached = idempotencyService.find(idempotencyKey)
        if (cached.isPresent) {
            val bodyJson = mapper.readTree(cached.get().responseBody)
            return ResponseEntity
                .status(cached.get().responseStatus)
                .body(mapper.treeToValue(bodyJson, CustomerResponse::class.java))
        }
        val customer =
            writeService.create(
                identityId = body.identity_id,
                name = body.name,
                email = body.email,
                phone = body.phone,
                primaryCityId = body.primary_city_id,
                actorId = actorId,
                correlationId = correlationId,
            )
        val response = customer.toResponse()
        idempotencyService.record(
            key = idempotencyKey,
            requestHash = "create",
            actorId = actorId,
            responseStatus = HttpStatus.CREATED.value(),
            responseBody = response,
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    // -----------------------------------------------------------------------
    // 1.3 PATCH /v1/customers/{id}
    // -----------------------------------------------------------------------
    @PatchMapping("/{customer_id}")
    @PreAuthorize(
        "hasAnyAuthority('ROLE_customer.write', 'ROLE_customer.admin', " +
            "'ROLE_platform.admin', 'ROLE_platform.super_admin')",
    )
    fun update(
        @PathVariable("customer_id") customerId: UUID,
        @Valid @RequestBody body: UpdateCustomerRequest,
        authentication: Authentication,
        httpRequest: HttpServletRequest,
    ): CustomerResponse {
        val correlationId = CorrelationContext.correlationId(httpRequest)
        val actorId = CorrelationContext.actorId(authentication)
        val customer = readService.get(customerId)
        val isAdmin = authentication.authorities.any {
            it.authority == "ROLE_customer.admin" ||
                it.authority == "ROLE_platform.admin" ||
                it.authority == "ROLE_platform.super_admin"
        }
        if (!isAdmin) {
            authorizeSelfOrAny(customer.identityId, authentication)
        }
        val updated =
            writeService.updateProfile(
                customerId = customerId,
                name = body.name,
                email = body.email,
                phone = body.phone,
                primaryCityId = body.primary_city_id,
                expectedRowVersion = body.expected_row_version,
                actorId = actorId,
                actorType = if (isAdmin) "admin" else "user",
                reason = null,
                correlationId = correlationId,
            )
        return updated.toResponse()
    }

    // -----------------------------------------------------------------------
    // 1.4 GET /v1/customers/{id}/kyc
    // -----------------------------------------------------------------------
    @GetMapping("/{customer_id}/kyc")
    @PreAuthorize(
        "hasAnyAuthority('ROLE_customer.read', 'ROLE_customer.admin', " +
            "'ROLE_customer.read.any', 'ROLE_platform.admin', 'ROLE_platform.super_admin')",
    )
    fun kyc(
        @PathVariable("customer_id") customerId: UUID,
        authentication: Authentication,
    ): KycResponse {
        val customer = readService.get(customerId)
        authorizeSelfOrAny(customer.identityId, authentication)
        return KycResponse(
            tier = customer.kycTier,
            verification_id = customer.kycVerificationId,
            verified_at = customer.kycVerifiedAt,
            limits = KycLimits(
                tier_0 = 0L,
                tier_1 = 50_000L,
                tier_2 = 500_000L,
                tier_3 = null,
            ),
        )
    }

    // -----------------------------------------------------------------------
    // 1.5 POST /v1/customers/{id}/kyc/upgrade
    // -----------------------------------------------------------------------
    @PostMapping("/{customer_id}/kyc/upgrade")
    @PreAuthorize(
        "hasAnyAuthority('ROLE_customer.write', 'ROLE_customer.admin', " +
            "'ROLE_platform.admin', 'ROLE_platform.super_admin')",
    )
    fun kycUpgrade(
        @PathVariable("customer_id") customerId: UUID,
        @Valid @RequestBody body: KycUpgradeRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: UUID,
        authentication: Authentication,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<CustomerResponse> {
        val correlationId = CorrelationContext.correlationId(httpRequest)
        val actorId = CorrelationContext.actorId(authentication)
        val cached = idempotencyService.find(idempotencyKey)
        if (cached.isPresent) {
            val bodyJson = mapper.readTree(cached.get().responseBody)
            return ResponseEntity
                .status(cached.get().responseStatus)
                .body(mapper.treeToValue(bodyJson, CustomerResponse::class.java))
        }
        val customer =
            kycService.upgrade(
                customerId = customerId,
                documentFileIds = body.document_file_ids,
                targetTier = body.target_tier,
                actorId = actorId,
                actorType = "user",
                correlationId = correlationId,
            )
        val response = customer.toResponse()
        idempotencyService.record(
            key = idempotencyKey,
            requestHash = "kyc-upgrade",
            actorId = actorId,
            responseStatus = HttpStatus.OK.value(),
            responseBody = response,
        )
        return ResponseEntity.ok(response)
    }

    // -----------------------------------------------------------------------
    // 1.6 POST /v1/customers/{id}/suspend
    // -----------------------------------------------------------------------
    @PostMapping("/{customer_id}/suspend")
    @PreAuthorize(
        "hasAnyAuthority('ROLE_customer.admin', 'ROLE_platform.admin', 'ROLE_platform.super_admin')",
    )
    fun suspend(
        @PathVariable("customer_id") customerId: UUID,
        @Valid @RequestBody body: SuspendRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: UUID,
        authentication: Authentication,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<CustomerResponse> {
        val correlationId = CorrelationContext.correlationId(httpRequest)
        val actorId = CorrelationContext.actorId(authentication)
        val cached = idempotencyService.find(idempotencyKey)
        if (cached.isPresent) {
            val bodyJson = mapper.readTree(cached.get().responseBody)
            return ResponseEntity
                .status(cached.get().responseStatus)
                .body(mapper.treeToValue(bodyJson, CustomerResponse::class.java))
        }
        val customer =
            writeService.suspend(
                customerId = customerId,
                reason = body.reason,
                note = body.note,
                actorId = actorId,
                actorType = "admin",
                correlationId = correlationId,
            )
        val response = customer.toResponse()
        idempotencyService.record(
            key = idempotencyKey,
            requestHash = "suspend",
            actorId = actorId,
            responseStatus = HttpStatus.OK.value(),
            responseBody = response,
        )
        return ResponseEntity.ok(response)
    }

    // -----------------------------------------------------------------------
    // 1.7 POST /v1/customers/{id}/reinstate
    // -----------------------------------------------------------------------
    @PostMapping("/{customer_id}/reinstate")
    @PreAuthorize(
        "hasAnyAuthority('ROLE_customer.admin', 'ROLE_platform.admin', 'ROLE_platform.super_admin')",
    )
    fun reinstate(
        @PathVariable("customer_id") customerId: UUID,
        @RequestBody(required = false) body: ReinstateRequest?,
        @RequestHeader("Idempotency-Key") idempotencyKey: UUID,
        authentication: Authentication,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<CustomerResponse> {
        val correlationId = CorrelationContext.correlationId(httpRequest)
        val actorId = CorrelationContext.actorId(authentication)
        val cached = idempotencyService.find(idempotencyKey)
        if (cached.isPresent) {
            val bodyJson = mapper.readTree(cached.get().responseBody)
            return ResponseEntity
                .status(cached.get().responseStatus)
                .body(mapper.treeToValue(bodyJson, CustomerResponse::class.java))
        }
        val customer =
            writeService.reinstate(
                customerId = customerId,
                note = body?.note,
                actorId = actorId,
                actorType = "admin",
                correlationId = correlationId,
            )
        val response = customer.toResponse()
        idempotencyService.record(
            key = idempotencyKey,
            requestHash = "reinstate",
            actorId = actorId,
            responseStatus = HttpStatus.OK.value(),
            responseBody = response,
        )
        return ResponseEntity.ok(response)
    }

    // -----------------------------------------------------------------------
    // 1.8 POST /v1/customers/{id}/disable
    // -----------------------------------------------------------------------
    @PostMapping("/{customer_id}/disable")
    @PreAuthorize(
        "hasAnyAuthority('ROLE_customer.admin', 'ROLE_platform.admin', 'ROLE_platform.super_admin')",
    )
    fun disable(
        @PathVariable("customer_id") customerId: UUID,
        @Valid @RequestBody body: DisableRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: UUID,
        authentication: Authentication,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<CustomerResponse> {
        val correlationId = CorrelationContext.correlationId(httpRequest)
        val actorId = CorrelationContext.actorId(authentication)
        val cached = idempotencyService.find(idempotencyKey)
        if (cached.isPresent) {
            val bodyJson = mapper.readTree(cached.get().responseBody)
            return ResponseEntity
                .status(cached.get().responseStatus)
                .body(mapper.treeToValue(bodyJson, CustomerResponse::class.java))
        }
        val customer =
            writeService.disable(
                customerId = customerId,
                reason = body.reason,
                note = body.note,
                actorId = actorId,
                actorType = "admin",
                correlationId = correlationId,
            )
        val response = customer.toResponse()
        idempotencyService.record(
            key = idempotencyKey,
            requestHash = "disable",
            actorId = actorId,
            responseStatus = HttpStatus.OK.value(),
            responseBody = response,
        )
        return ResponseEntity.ok(response)
    }

    // -----------------------------------------------------------------------
    // 1.9 POST /v1/customers/{id}/erase
    // -----------------------------------------------------------------------
    @PostMapping("/{customer_id}/erase")
    @PreAuthorize(
        "hasAnyAuthority('ROLE_customer.admin', 'ROLE_platform.admin', 'ROLE_platform.super_admin')",
    )
    fun erase(
        @PathVariable("customer_id") customerId: UUID,
        @RequestBody(required = false) body: EraseRequest?,
        @RequestHeader("Idempotency-Key") idempotencyKey: UUID,
        authentication: Authentication,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<EraseResponse> {
        val correlationId = CorrelationContext.correlationId(httpRequest)
        val actorId = CorrelationContext.actorId(authentication)
        val cached = idempotencyService.find(idempotencyKey)
        if (cached.isPresent) {
            val bodyJson = mapper.readTree(cached.get().responseBody)
            return ResponseEntity
                .status(cached.get().responseStatus)
                .body(mapper.treeToValue(bodyJson, EraseResponse::class.java))
        }
        val customer =
            writeService.erase(
                customerId = customerId,
                legalBasis = body?.legal_basis,
                note = body?.note,
                actorId = actorId,
                actorType = "admin",
                correlationId = correlationId,
            )
        val response =
            EraseResponse(
                customer = customer.toResponse(),
                warnings = emptyList(),
                correlation_id = correlationId,
            )
        idempotencyService.record(
            key = idempotencyKey,
            requestHash = "erase",
            actorId = actorId,
            responseStatus = HttpStatus.OK.value(),
            responseBody = response,
        )
        return ResponseEntity.ok(response)
    }

    // -----------------------------------------------------------------------
    // 1.10 PUT /v1/customers/{id}/default-payment-method/{pm_id}
    // -----------------------------------------------------------------------
    @PutMapping("/{customer_id}/default-payment-method/{payment_method_id}")
    @PreAuthorize(
        "hasAnyAuthority('ROLE_customer.write', 'ROLE_customer.admin', " +
            "'ROLE_platform.admin', 'ROLE_platform.super_admin')",
    )
    fun setDefaultPaymentMethod(
        @PathVariable("customer_id") customerId: UUID,
        @PathVariable("payment_method_id") paymentMethodId: UUID,
        authentication: Authentication,
        httpRequest: HttpServletRequest,
    ): SetDefaultMethodResponse {
        val correlationId = CorrelationContext.correlationId(httpRequest)
        val actorId = CorrelationContext.actorId(authentication)
        val customer = readService.get(customerId)
        authorizeSelfOrAny(customer.identityId, authentication)
        val updated =
            writeService.setDefaultPaymentMethod(
                customerId = customerId,
                paymentMethodId = paymentMethodId,
                actorId = actorId,
                actorType = "user",
                correlationId = correlationId,
            )
        return SetDefaultMethodResponse(
            customer = updated.toResponse(),
            correlation_id = correlationId,
        )
    }

    // -----------------------------------------------------------------------
    // 1.11 PUT /v1/customers/{id}/default-address/{address_id}
    // -----------------------------------------------------------------------
    @PutMapping("/{customer_id}/default-address/{address_id}")
    @PreAuthorize(
        "hasAnyAuthority('ROLE_customer.write', 'ROLE_customer.admin', " +
            "'ROLE_platform.admin', 'ROLE_platform.super_admin')",
    )
    fun setDefaultAddress(
        @PathVariable("customer_id") customerId: UUID,
        @PathVariable("address_id") addressId: UUID,
        authentication: Authentication,
        httpRequest: HttpServletRequest,
    ): SetDefaultAddressResponse {
        val correlationId = CorrelationContext.correlationId(httpRequest)
        val actorId = CorrelationContext.actorId(authentication)
        // Address ownership is enforced by the (someday) addresses service;
        // here we just bind the reference as the docs allow.
        val updated =
            writeService.setDefaultAddress(
                customerId = customerId,
                addressId = addressId,
                actorId = actorId,
                actorType = "user",
                correlationId = correlationId,
            )
        return SetDefaultAddressResponse(
            customer = updated.toResponse(),
            correlation_id = correlationId,
        )
    }

    // -----------------------------------------------------------------------
    // GET /v1/customers/{id}/kyc/history
    // -----------------------------------------------------------------------
    @GetMapping("/{customer_id}/kyc/history")
    @PreAuthorize(
        "hasAnyAuthority('ROLE_customer.read', 'ROLE_customer.admin', " +
            "'ROLE_customer.read.any', 'ROLE_platform.admin', 'ROLE_platform.super_admin')",
    )
    fun kycHistory(
        @PathVariable("customer_id") customerId: UUID,
        authentication: Authentication,
    ): KycHistoryResponse {
        val customer = readService.get(customerId)
        authorizeSelfOrAny(customer.identityId, authentication)
        val history = kycHistoryService.history(customerId)
        return KycHistoryResponse(items = history.map { it.toItem() })
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------
    private fun authorizeSelfOrAny(
        identityId: UUID,
        authentication: Authentication,
    ) {
        val hasAny = authentication.authorities.any {
            it.authority == "ROLE_customer.read.any" ||
                it.authority == "ROLE_customer.admin" ||
                it.authority == "ROLE_platform.admin" ||
                it.authority == "ROLE_platform.super_admin"
        }
        if (hasAny) return
        val principal = runCatching { UUID.fromString(authentication.name) }.getOrNull()
        if (principal != identityId) {
            throw ApiException(
                HttpStatus.FORBIDDEN,
                "FORBIDDEN",
                "X-User-Id / principal must match the customer id",
            )
        }
    }
}
