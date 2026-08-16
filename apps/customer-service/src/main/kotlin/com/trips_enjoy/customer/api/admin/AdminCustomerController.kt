package com.trips_enjoy.customer.api.admin

import com.trips_enjoy.customer.api.ApiException
import com.trips_enjoy.customer.api.CustomerResponse
import com.trips_enjoy.customer.api.toResponse
import com.trips_enjoy.customer.application.AdminAuditPublisher
import com.trips_enjoy.customer.application.CustomerReadService
import com.trips_enjoy.customer.application.CustomerWriteService
import com.trips_enjoy.customer.application.KycService
import com.trips_enjoy.customer.util.CorrelationContext
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * TECH §10.4 — admin BFF endpoints exposed by customer-service.
 *
 *   - GET   /admin/v1/customers/{id}              : full profile (incl. PII, requires `X-Audit-Reason`)
 *   - POST  /admin/v1/customers/{id}/kyc/override : force-set the KYC tier (admin override)
 *   - POST  /admin/v1/customers/{id}/merge        : merge two records (post-fraud / post-KYC-correction)
 *   - POST  /admin/v1/customers/{id}/pseudonymize : erase PII while preserving customer_id
 *
 * Every call emits an `audit.admin.customer.v1` row via
 * [AdminAuditPublisher] so the platform admin audit chain is complete.
 */
@RestController
@RequestMapping("/admin/v1/customers")
class AdminCustomerController(
    private val readService: CustomerReadService,
    private val writeService: CustomerWriteService,
    private val kycService: KycService,
    private val auditPublisher: AdminAuditPublisher,
) {
    @GetMapping("/{customer_id}")
    @PreAuthorize(
        "hasAnyAuthority('ROLE_customer.admin', 'ROLE_platform.admin', 'ROLE_platform.super_admin')",
    )
    fun get(
        @PathVariable("customer_id") customerId: UUID,
        @RequestHeader("X-Audit-Reason") reason: String,
        authentication: Authentication,
        httpRequest: HttpServletRequest,
    ): CustomerResponse {
        if (reason.length < 8) {
            throw ApiException(
                HttpStatus.BAD_REQUEST,
                "AUDIT_REASON_REQUIRED",
                "X-Audit-Reason header is required and must be 8+ characters",
            )
        }
        val customer = readService.get(customerId)
        publishAudit(
            authentication = authentication,
            endpoint = "GET /admin/v1/customers/{id}",
            action = "admin_read",
            reasonCode = reason,
            result = "200",
            httpRequest = httpRequest,
        )
        return customer.toResponse()
    }

    @PostMapping("/{customer_id}/kyc/override")
    @PreAuthorize(
        "hasAnyAuthority('ROLE_customer.admin', 'ROLE_platform.admin', 'ROLE_platform.super_admin')",
    )
    fun overrideKyc(
        @PathVariable("customer_id") customerId: UUID,
        @Valid @RequestBody body: AdminOverrideKycTierRequest,
        @RequestHeader("X-Audit-Reason") reason: String,
        authentication: Authentication,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<CustomerResponse> {
        val correlationId = CorrelationContext.correlationId(httpRequest)
        val actorId = CorrelationContext.actorId(authentication)
        val customer =
            kycService.adminOverrideTier(
                customerId = customerId,
                toTier = body.to_tier,
                reason = reason,
                actorId = actorId,
                actorType = "admin",
                correlationId = correlationId,
            )
        publishAudit(
            authentication = authentication,
            endpoint = "POST /admin/v1/customers/{id}/kyc/override",
            action = "admin_kyc_override",
            reasonCode = reason,
            result = "200",
            httpRequest = httpRequest,
        )
        return ResponseEntity.ok(customer.toResponse())
    }

    @PostMapping("/{customer_id}/merge")
    @PreAuthorize(
        "hasAnyAuthority('ROLE_customer.admin', 'ROLE_platform.admin', 'ROLE_platform.super_admin')",
    )
    fun merge(
        @PathVariable("customer_id") customerId: UUID,
        @Valid @RequestBody body: AdminMergeRequest,
        @RequestHeader("X-Audit-Reason") reason: String,
        authentication: Authentication,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<AdminMergeResponse> {
        val correlationId = CorrelationContext.correlationId(httpRequest)
        val sourceId =
            runCatching { UUID.fromString(body.source_customer_id) }.getOrElse {
                throw ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "source_customer_id must be a UUID",
                )
            }
        val targetId =
            runCatching { UUID.fromString(body.target_customer_id) }.getOrElse {
                throw ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "target_customer_id must be a UUID",
                )
            }
        if (sourceId != customerId) {
            throw ApiException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "source_customer_id must match the path customer_id",
            )
        }
        // The merge itself is a multi-step procedure (rename references in
        // payment-service, food-order-service, etc.); Phase 1 only records
        // the intent and emits the audit row. A follow-up migration will
        // wire the cross-service reference rewriting.
        publishAudit(
            authentication = authentication,
            endpoint = "POST /admin/v1/customers/{id}/merge",
            action = "admin_merge",
            reasonCode = reason,
            result = "200",
            httpRequest = httpRequest,
        )
        return ResponseEntity.ok(
            AdminMergeResponse(
                target_customer_id = targetId.toString(),
                source_customer_id = sourceId.toString(),
                correlation_id = correlationId.toString(),
            ),
        )
    }

    @PostMapping("/{customer_id}/pseudonymize")
    @PreAuthorize(
        "hasAnyAuthority('ROLE_customer.admin', 'ROLE_platform.admin', 'ROLE_platform.super_admin')",
    )
    fun pseudonymize(
        @PathVariable("customer_id") customerId: UUID,
        @RequestBody(required = false) body: AdminPseudonymizeRequest?,
        @RequestHeader("X-Audit-Reason") reason: String,
        authentication: Authentication,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<CustomerResponse> {
        val correlationId = CorrelationContext.correlationId(httpRequest)
        val actorId = CorrelationContext.actorId(authentication)
        val customer =
            writeService.erase(
                customerId = customerId,
                legalBasis = "admin_pseudonymize",
                note = body?.reason ?: reason,
                actorId = actorId,
                actorType = "admin",
                correlationId = correlationId,
            )
        publishAudit(
            authentication = authentication,
            endpoint = "POST /admin/v1/customers/{id}/pseudonymize",
            action = "admin_pseudonymize",
            reasonCode = reason,
            result = "200",
            httpRequest = httpRequest,
        )
        return ResponseEntity.ok(customer.toResponse())
    }

    private fun publishAudit(
        authentication: Authentication,
        endpoint: String,
        action: String,
        reasonCode: String?,
        result: String,
        httpRequest: HttpServletRequest,
    ) {
        auditPublisher.publish(
            actorId = CorrelationContext.actorId(authentication),
            actorUsername = authentication.name,
            actorRoles = authentication.authorities.mapNotNull { it.authority },
            endpoint = endpoint,
            action = action,
            targetResource = httpRequest.requestURI,
            reasonCode = reasonCode,
            requestId = httpRequest.getHeader("X-Request-Id") ?: httpRequest.getAttribute("correlationId")?.toString(),
            traceId = httpRequest.getHeader("traceparent"),
            result = result,
            durationMs = 0L,
        )
    }
}
