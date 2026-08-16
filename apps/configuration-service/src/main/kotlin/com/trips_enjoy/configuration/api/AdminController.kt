package com.trips_enjoy.configuration.api

import com.trips_enjoy.configuration.application.ConfigurationIngestService
import com.trips_enjoy.configuration.util.CorrelationContext
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Admin BFF endpoints (TECH.md §10.4). Mounted at `/admin/v1/config/`.
 * Per the super-admin preset (memory `uber-super-admin-preset-management`),
 * the minimum role to access these is `configuration.admin`.
 */
@RestController
@RequestMapping("/admin/v1/config")
class AdminController(
    private val ingestService: ConfigurationIngestService,
) {
    @PostMapping("/{key}/rollback")
    @PreAuthorize(
        "hasAnyAuthority('ROLE_configuration.admin', 'ROLE_platform.super_admin')",
    )
    fun rollback(
        @PathVariable key: String,
        @Valid @RequestBody body: AdminRollbackRequest,
        authentication: Authentication,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<WriteResponse> {
        val result =
            ingestService.rollback(
                key = key,
                toVersion = body.to_version,
                reason = body.reason,
                actorId = CorrelationContext.actorId(authentication),
                actorIp = CorrelationContext.clientIp(httpRequest),
                correlationId = CorrelationContext.correlationId(httpRequest),
            )
        val response =
            WriteResponse(
                document_id = result.documentId,
                key = result.key,
                version = result.version,
                value = result.value,
                matched_scope_type = result.matchedScopeType,
                matched_scope_id = result.matchedScopeId,
                impact = WriteImpact(consumers_reloading = result.consumerReload),
                correlation_id = result.correlationId,
            )
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @PostMapping("/bulk-publish")
    @PreAuthorize(
        "hasAnyAuthority('ROLE_configuration.admin', 'ROLE_platform.super_admin')",
    )
    fun bulkPublish(
        @Valid @RequestBody body: AdminBulkPublishRequest,
    ): ResponseEntity<Map<String, Any>> {
        // The actual write path is via the standard PUT /v1/configurations/{key}/versions
        // endpoint; this admin endpoint just acknowledges the operator intent. The
        // synchronous bulk-publish itself is left to the operator to drive via the
        // version API for each key (deliberately, to keep the audit trail explicit).
        return ResponseEntity.ok(
            mapOf(
                "acknowledged" to body.keys,
                "reason" to body.reason,
                "message" to "use PUT /v1/configurations/{key}/versions per key to commit",
            ),
        )
    }
}
