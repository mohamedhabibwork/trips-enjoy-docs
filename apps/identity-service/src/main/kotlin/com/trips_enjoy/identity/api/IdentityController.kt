package com.trips_enjoy.identity.api

import com.trips_enjoy.identity.application.IdentityApplicationService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/v1/identities")
class IdentityController(private val service: IdentityApplicationService) {
    @GetMapping("/{identityId}")
    @PreAuthorize("hasAnyAuthority('SCOPE_identity.read', 'ROLE_identity.read')")
    fun get(@PathVariable identityId: UUID) = service.get(identityId)

    @GetMapping(params = ["kc_sub", "realm"])
    @PreAuthorize("hasAnyAuthority('SCOPE_identity.read', 'ROLE_identity.read')")
    fun getBySubject(@RequestParam("kc_sub") subject: String, @RequestParam realm: String) = service.getBySubject(subject, realm)

    @GetMapping("/{identityId}/claims")
    @PreAuthorize("hasAnyAuthority('SCOPE_identity.read', 'ROLE_identity.read')")
    fun claims(@PathVariable identityId: UUID): Map<String, Any?> = service.getClaims(identityId)

    @GetMapping("/{identityId}/sessions")
    @PreAuthorize("hasAnyAuthority('SCOPE_identity.read', 'ROLE_identity.read', 'ROLE_identity.admin', 'ROLE_platform.super_admin')")
    fun sessions(@PathVariable identityId: UUID): List<Map<String, Any?>> = service.listSessions(identityId)

    @PostMapping("/introspect")
    @PreAuthorize("hasAnyAuthority('SCOPE_identity.read', 'ROLE_identity.read')")
    fun introspect(@Valid @RequestBody request: IntrospectionRequest) = service.introspect(request.token)

    @PostMapping
    @PreAuthorize("hasAnyAuthority('SCOPE_identity.write', 'ROLE_identity.write')")
    fun create(@Valid @RequestBody request: CreateIdentityRequest, @RequestHeader("Idempotency-Key") key: UUID, authentication: Authentication): ResponseEntity<IdentityResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(service.create(request, actor(authentication), key))

    @PatchMapping("/{identityId}")
    @PreAuthorize("hasAnyAuthority('ROLE_identity.admin', 'ROLE_platform.super_admin')")
    fun update(@PathVariable identityId: UUID, @Valid @RequestBody request: UpdateIdentityRequest, @RequestHeader("X-Audit-Reason") auditReason: String, authentication: Authentication) = service.update(identityId, request, actor(authentication))

    @PostMapping("/{identityId}/suspend")
    @PreAuthorize("hasAnyAuthority('ROLE_identity.admin', 'ROLE_platform.super_admin')")
    fun suspend(@PathVariable identityId: UUID, @Valid @RequestBody request: SuspensionRequest, @RequestHeader("Idempotency-Key") key: UUID, @RequestHeader("X-Audit-Reason") auditReason: String, authentication: Authentication) = service.suspend(identityId, request, actor(authentication), key)

    @PostMapping("/{identityId}/disable")
    @PreAuthorize("hasAnyAuthority('ROLE_identity.admin', 'ROLE_platform.super_admin')")
    fun disable(@PathVariable identityId: UUID, @Valid @RequestBody request: DisableRequest, @RequestHeader("Idempotency-Key") key: UUID, @RequestHeader("X-Audit-Reason") auditReason: String, authentication: Authentication) = service.disable(identityId, request, actor(authentication), key)

    @PostMapping("/{identityId}/reinstate")
    @PreAuthorize("hasAnyAuthority('ROLE_identity.admin', 'ROLE_platform.super_admin')")
    fun reinstate(@PathVariable identityId: UUID, @Valid @RequestBody request: ReinstateRequest, @RequestHeader("Idempotency-Key") key: UUID, @RequestHeader("X-Audit-Reason") auditReason: String, authentication: Authentication) = service.reinstate(identityId, request, actor(authentication), key)

    @PostMapping("/{identityId}/erase")
    @PreAuthorize("hasAnyAuthority('ROLE_identity.admin', 'ROLE_platform.super_admin')")
    fun erase(@PathVariable identityId: UUID, @Valid @RequestBody request: EraseRequest, @RequestHeader("Idempotency-Key") key: UUID, @RequestHeader("X-Audit-Reason") auditReason: String, authentication: Authentication) = service.erase(identityId, request, actor(authentication), key)

    @PostMapping("/{identityId}/logout-everywhere")
    @PreAuthorize("hasAnyAuthority('ROLE_identity.admin', 'ROLE_platform.super_admin')")
    fun logout(@PathVariable identityId: UUID, @Valid @RequestBody request: LogoutRequest, @RequestHeader("Idempotency-Key") key: UUID, @RequestHeader("X-Audit-Reason") auditReason: String, authentication: Authentication) = service.logout(identityId, request, actor(authentication), key)

    private fun actor(authentication: Authentication): UUID = runCatching { UUID.fromString(authentication.name) }.getOrElse { UUID(0, 0) }
}
