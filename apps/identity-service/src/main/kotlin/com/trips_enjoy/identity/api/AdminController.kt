package com.trips_enjoy.identity.api.admin

import com.trips_enjoy.identity.api.ApiException
import com.trips_enjoy.identity.application.AdminAuditPublisher
import com.trips_enjoy.identity.application.IdentityApplicationService
import com.trips_enjoy.identity.application.IdentityClaimsService
import com.trips_enjoy.identity.application.RoleAssignmentService
import com.trips_enjoy.identity.domain.IdentityRepository
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@RestController
@RequestMapping("/admin/v1/identities")
class AdminController(
    private val identities: IdentityRepository,
    private val roleService: RoleAssignmentService,
    private val claimsService: IdentityClaimsService,
    private val identityService: IdentityApplicationService,
    private val auditPublisher: AdminAuditPublisher,
    @Value("\${identity.request-signing-secret:}") private val requestSigningSecret: String,
) {
    /** §1.11 — list roles. */
    @GetMapping("/{identityId}/roles")
    @PreAuthorize("hasAnyAuthority('ROLE_platform.admin', 'ROLE_platform.super_admin')")
    fun listRoles(@PathVariable identityId: UUID, authentication: Authentication): ResponseEntity<AdminRolesResponse> {
        val result = roleService.listRoles(identityId)
        publishAdminAudit(identities.findById(identityId).orElse(null), authentication, "GET /admin/v1/identities/{id}/roles", "list_roles", null, "200", 0)
        return ResponseEntity.ok(result)
    }

    /** §1.12 — grant role. */
    @PostMapping("/{identityId}/roles/{role}")
    @PreAuthorize("hasAnyAuthority('ROLE_platform.admin', 'ROLE_platform.super_admin')")
    fun grant(
        @PathVariable identityId: UUID,
        @PathVariable role: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: UUID,
        @RequestHeader("X-Audit-Reason") @jakarta.validation.constraints.Size(min = 8, max = 512) auditReason: String,
        @RequestHeader(value = "X-Break-Glass-Cosigner", required = false) cosigner: String?,
        @RequestHeader(value = "X-Signature", required = false) signature: String?,
        @Valid @RequestBody body: AdminRoleGrantRequest,
        request: HttpServletRequest,
        authentication: Authentication,
    ): ResponseEntity<AdminRolesResponse> {
        validateGates(role, auditReason, cosigner, signature, request, body, idempotencyKey)
        val identity = identities.findById(identityId).orElseThrow {
            ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Identity not found")
        }
        val cosignerUuid = cosigner?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val result = roleService.grant(
            identity = identity,
            role = role,
            actor = actorOf(authentication),
            cosigner = cosignerUuid,
            breakGlass = cosignerUuid != null,
            signature = signature,
            preset = body.preset,
            reasonCode = body.reason_code,
            endpoint = "POST /admin/v1/identities/{id}/roles/{role}",
        )
        publishAdminAudit(identity, authentication, "POST /admin/v1/identities/{id}/roles/{role}", "grant_role", body.reason_code, "200", 0)
        return ResponseEntity.ok(result)
    }

    /** §1.13 — revoke role. */
    @DeleteMapping("/{identityId}/roles/{role}")
    @PreAuthorize("hasAnyAuthority('ROLE_platform.admin', 'ROLE_platform.super_admin')")
    fun revoke(
        @PathVariable identityId: UUID,
        @PathVariable role: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: UUID,
        @RequestHeader("X-Audit-Reason") @jakarta.validation.constraints.Size(min = 8, max = 512) auditReason: String,
        @RequestHeader(value = "X-Break-Glass-Cosigner", required = false) cosigner: String?,
        @RequestHeader(value = "X-Signature", required = false) signature: String?,
        @Valid @RequestBody body: AdminRoleRevokeRequest,
        request: HttpServletRequest,
        authentication: Authentication,
    ): ResponseEntity<AdminRolesResponse> {
        validateGates(role, auditReason, cosigner, signature, request, body, idempotencyKey)
        val identity = identities.findById(identityId).orElseThrow {
            ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Identity not found")
        }
        val cosignerUuid = cosigner?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val result = roleService.revoke(
            identity = identity,
            role = role,
            actor = actorOf(authentication),
            cosigner = cosignerUuid,
            breakGlass = cosignerUuid != null,
            signature = signature,
            preset = body.preset,
            reasonCode = body.reason_code,
            endpoint = "DELETE /admin/v1/identities/{id}/roles/{role}",
        )
        publishAdminAudit(identity, authentication, "DELETE /admin/v1/identities/{id}/roles/{role}", "revoke_role", body.reason_code, "200", 0)
        return ResponseEntity.ok(result)
    }

    /** TECH §10.4 row 1 — admin suspend. */
    @PostMapping("/{identityId}/suspend")
    @PreAuthorize("hasAnyAuthority('ROLE_platform.admin', 'ROLE_platform.super_admin')")
    fun suspend(
        @PathVariable identityId: UUID,
        @RequestHeader("Idempotency-Key") idempotencyKey: UUID,
        @RequestHeader("X-Audit-Reason") @jakarta.validation.constraints.Size(min = 8, max = 512) auditReason: String,
        @Valid @RequestBody body: AdminSuspendRequest,
        authentication: Authentication,
    ): ResponseEntity<AdminActionResponse> {
        val result = identityService.suspend(
            identityId,
            com.trips_enjoy.identity.api.SuspensionRequest(body.reason, body.note, body.expected_duration_days),
            actorOf(authentication),
            idempotencyKey,
        )
        val identity = identities.findById(identityId).orElse(null)
        publishAdminAudit(identity, authentication, "POST /admin/v1/identities/{id}/suspend", "admin_suspend", auditReason, "200", 0)
        return ResponseEntity.ok(AdminActionResponse(identityId, result.status))
    }

    /** TECH §10.4 row 2 — admin unsuspend (same as reinstate). */
    @PostMapping("/{identityId}/unsuspend")
    @PreAuthorize("hasAnyAuthority('ROLE_platform.admin', 'ROLE_platform.super_admin')")
    fun unsuspend(
        @PathVariable identityId: UUID,
        @RequestHeader("Idempotency-Key") idempotencyKey: UUID,
        @RequestHeader("X-Audit-Reason") @jakarta.validation.constraints.Size(min = 8, max = 512) auditReason: String,
        @Valid @RequestBody body: AdminReinstateRequest,
        authentication: Authentication,
    ): ResponseEntity<AdminActionResponse> {
        val result = identityService.reinstate(
            identityId,
            com.trips_enjoy.identity.api.ReinstateRequest(body.note),
            actorOf(authentication),
            idempotencyKey,
        )
        val identity = identities.findById(identityId).orElse(null)
        publishAdminAudit(identity, authentication, "POST /admin/v1/identities/{id}/unsuspend", "admin_unsuspend", auditReason, "200", 0)
        return ResponseEntity.ok(AdminActionResponse(identityId, result.status))
    }

    /** TECH §10.4 row 3 — force-refresh cached claims. */
    @PostMapping("/{identityId}/force-claims-refresh")
    @PreAuthorize("hasAnyAuthority('ROLE_identity.admin', 'ROLE_platform.super_admin')")
    fun forceClaimsRefresh(
        @PathVariable identityId: UUID,
        authentication: Authentication,
    ): ResponseEntity<ForceClaimsRefreshResponse> {
        val refreshed = claimsService.forceRefresh(identityId)
        val identity = identities.findById(identityId).orElse(null)
        publishAdminAudit(identity, authentication, "POST /admin/v1/identities/{id}/force-claims-refresh", "force_claims_refresh", null, "200", 0)
        return ResponseEntity.ok(ForceClaimsRefreshResponse(identityId, refreshed.lastRefreshedAt, "service"))
    }

    /**
     * Enforces the super-admin grant gates per TECH §10.5 + INTEGRATION §1.12.
     *
     * For `role == "platform.super_admin"`:
     *   - `X-Audit-Reason` length is already enforced by `@Size(min = 8, max = 512)`.
     *   - `X-Break-Glass-Cosigner` is required and MUST differ from the caller.
     *   - `X-Signature` MUST verify HMAC-SHA256(t=<unix>,v1=<hex>) over `body || timestamp`
     *     using the configured `identity.request-signing-secret`.
     *
     * Off-hours (00:00–06:00 UTC) forces co-signature for non-super roles too.
     *
     * MFA step-up and IP allowlist are documented but enforced at the gateway
     * (TECH §10.5: "admin port is reachable only from the admin-service,
     * platform-ops, and platform-engineering namespaces + bastion").
     */
    private fun validateGates(
        role: String,
        auditReason: String,
        cosignerHeader: String?,
        signatureHeader: String?,
        request: HttpServletRequest,
        body: Any,
        idempotencyKey: UUID,
    ) {
        if (auditReason.length < 8) {
            throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "X-Audit-Reason must be at least 8 chars")
        }
        val now = Instant.now()
        val offHours = now.atZone(java.time.ZoneOffset.UTC).hour in RoleAssignmentService.OFF_HOURS_START_UTC until RoleAssignmentService.OFF_HOURS_END_UTC
        val isSuperRole = role in RoleAssignmentService.SUPER_ADMIN_ROLES
        if (isSuperRole) {
            val cosigner = cosignerHeader?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: throw ApiException(HttpStatus.FORBIDDEN, "CO_SIGNER_REQUIRED", "X-Break-Glass-Cosigner is required for platform.super_admin grants")
            val caller = runCatching { UUID.fromString(request.getAttribute("caller_id")?.toString() ?: "") }.getOrNull()
            if (caller != null && caller == cosigner) {
                throw ApiException(HttpStatus.FORBIDDEN, "COSIGNER_INVALID", "Self-co-sign is not permitted")
            }
            // HMAC verification
            if (signatureHeader.isNullOrBlank() || requestSigningSecret.isBlank()) {
                throw ApiException(HttpStatus.FORBIDDEN, "SIGNATURE_INVALID", "X-Signature is required for platform.super_admin grants")
            }
            if (!verifySignature(signatureHeader, body, idempotencyKey)) {
                throw ApiException(HttpStatus.FORBIDDEN, "SIGNATURE_INVALID", "X-Signature did not verify")
            }
        } else if (offHours) {
            if (cosignerHeader.isNullOrBlank()) {
                throw ApiException(HttpStatus.FORBIDDEN, "OFF_HOURS_NOT_ALLOWED", "Co-signature is required during off-hours (00:00–06:00 UTC)")
            }
        }
    }

    private fun verifySignature(signatureHeader: String, body: Any, idempotencyKey: UUID): Boolean {
        // Format: "t=<unix>,v1=<hex>"
        val parts = signatureHeader.split(",").associate {
            val (k, v) = it.split("=", limit = 2)
            k to v
        }
        val t = parts["t"]?.toLongOrNull() ?: return false
        val v1 = parts["v1"] ?: return false
        val timestamp = Instant.ofEpochSecond(t)
        // 5-minute window
        if (Math.abs(java.time.Duration.between(timestamp, Instant.now()).seconds) > 300) return false
        val payload = (body.toString() + ":" + t + ":" + idempotencyKey).toByteArray(StandardCharsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(requestSigningSecret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val expected = mac.doFinal(payload).joinToString("") { "%02x".format(it) }
        return MessageDigest.isEqual(expected.toByteArray(StandardCharsets.UTF_8), v1.toByteArray(StandardCharsets.UTF_8))
    }

    private fun actorOf(authentication: Authentication): UUID = runCatching { UUID.fromString(authentication.name) }.getOrElse { UUID(0, 0) }

    private fun publishAdminAudit(identity: com.trips_enjoy.identity.domain.Identity?, authentication: Authentication, endpoint: String, action: String, reason: String?, result: String, durationMs: Long) {
        if (identity == null) return
        auditPublisher.publish(
            identity = identity,
            actorId = actorOf(authentication),
            actorUsername = authentication.name,
            actorRoles = authentication.authorities.mapNotNull { it.authority },
            endpoint = endpoint,
            action = action,
            reasonCode = reason,
            requestId = null,
            traceId = null,
            result = result,
            durationMs = durationMs,
        )
    }
}
