package com.trips_enjoy.audit.api

import com.trips_enjoy.audit.application.AuditSearchService
import com.trips_enjoy.audit.application.AuditVerifyService
import com.trips_enjoy.audit.application.LitigationHoldService
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * The v1 audit API. Mounted at /v1/audit per INTEGRATION §1.
 *
 * RBAC per TECH §10 + SRS §11:
 *   - audit.read → /v1/audit/search, /v1/audit/events/{id}
 *   - audit.admin → /v1/audit/verify/{id}, /v1/audit/litigation-hold
 *
 * Every read carries an `X-Audit-Reason` header per FR--011 — auditors
 * authenticate and then declare why they're reading.
 */
@RestController
@RequestMapping("/v1/audit")
class AuditController(
    private val searchService: AuditSearchService,
    private val verifyService: AuditVerifyService,
    private val litigationHoldService: LitigationHoldService,
) {

    @PostMapping("/search")
    @PreAuthorize("hasAnyAuthority('ROLE_audit.read', 'ROLE_audit.admin', 'ROLE_platform.admin', 'ROLE_platform.super_admin')")
    fun search(
        @Valid @RequestBody request: AuditSearchRequest,
        @RequestHeader(value = "X-Audit-Reason", required = false) auditReason: String?,
        authentication: Authentication,
        httpRequest: HttpServletRequest,
    ): AuditSearchResponse {
        val reason = auditReason ?: request.reason
        if (reason.isBlank()) {
            throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "X-Audit-Reason header (or request.reason) is required")
        }
        return searchService.search(
            query = request.query,
            limit = request.limit,
            cursor = request.cursor,
            reason = reason,
            actorId = actorId(authentication),
            actorIp = clientIp(httpRequest),
            correlationId = correlationId(httpRequest),
        )
    }

    @GetMapping("/events/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_audit.read', 'ROLE_audit.admin', 'ROLE_platform.admin', 'ROLE_platform.super_admin')")
    fun getEvent(
        @PathVariable id: UUID,
        @RequestHeader("X-Audit-Reason") reason: String,
        authentication: Authentication,
        httpRequest: HttpServletRequest,
    ): AuditEventDetail =
        searchService.getById(
            id = id,
            actorId = actorId(authentication),
            actorIp = clientIp(httpRequest),
            reason = reason,
            correlationId = correlationId(httpRequest),
        )

    @GetMapping("/verify/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_audit.admin', 'ROLE_platform.super_admin')")
    fun verify(@PathVariable id: UUID): ResponseEntity<VerifyResponse> {
        val result = verifyService.verify(id)
        return if (result.verified) {
            ResponseEntity.ok(result)
        } else {
            ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(result)
        }
    }

    @PostMapping("/litigation-hold")
    @PreAuthorize("hasAnyAuthority('ROLE_audit.admin', 'ROLE_platform.super_admin')")
    fun createLitigationHold(
        @Valid @RequestBody request: LitigationHoldRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: UUID,
        @RequestHeader("X-Audit-Reason") reason: String,
        authentication: Authentication,
    ): ResponseEntity<LitigationHoldResponse> {
        if (request.reason != reason) {
            throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "request.reason and X-Audit-Reason must match")
        }
        val response = litigationHoldService.create(request, actorId(authentication))
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @GetMapping("/litigation-hold")
    @PreAuthorize("hasAnyAuthority('ROLE_audit.admin', 'ROLE_platform.super_admin')")
    fun listLitigationHolds(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): List<LitigationHoldResponse> =
        litigationHoldService.list(org.springframework.data.domain.PageRequest.of(page, size))

    private fun actorId(authentication: Authentication): UUID = runCatching { UUID.fromString(authentication.name) }.getOrElse { UUID(0, 0) }

    private fun clientIp(request: HttpServletRequest): String? =
        request.getHeader("X-Forwarded-For")?.substringBefore(',')?.trim()
            ?: request.remoteAddr

    private fun correlationId(request: HttpServletRequest): UUID =
        runCatching { UUID.fromString(request.getAttribute("correlationId")?.toString() ?: "") }.getOrNull()
            ?: UUID.randomUUID()
}
