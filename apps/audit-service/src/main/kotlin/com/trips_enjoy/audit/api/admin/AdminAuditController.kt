package com.trips_enjoy.audit.api.admin

import com.trips_enjoy.audit.api.ApiException
import com.trips_enjoy.audit.application.AdminAuditPublisher
import com.trips_enjoy.audit.application.AuditSearchService
import com.trips_enjoy.audit.application.ExportService
import com.trips_enjoy.audit.domain.AuditReadLogRepository
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * TECH §10.4 — admin endpoints exposed by audit-service.
 *
 *   - GET /admin/v1/audit/search       : full-text search across the audit log
 *     (delegates to the read API; min role `platform.admin`)
 *   - POST /admin/v1/audit/export      : ad-hoc export to S3 (min role `platform.admin`)
 *   - POST /admin/v1/audit/reindex     : rebuild the audit search index
 *     (min role `audit.admin`)
 *
 * Every call emits an `audit.admin.audit.v1` row to the outbox via
 * [AdminAuditPublisher] so platform admins are themselves auditable.
 */
@RestController
@RequestMapping("/admin/v1/audit")
class AdminAuditController(
    private val searchService: AuditSearchService,
    private val exportService: ExportService,
    private val readLog: AuditReadLogRepository,
    private val auditPublisher: AdminAuditPublisher,
) {

    @PostMapping("/search")
    @PreAuthorize("hasAnyAuthority('ROLE_platform.admin', 'ROLE_platform.super_admin', 'ROLE_audit.admin')")
    fun search(
        @Valid @RequestBody request: AdminAuditSearchRequest,
        authentication: Authentication,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<Map<String, Any?>> {
        val response = searchService.search(
            query = com.trips_enjoy.audit.api.AuditSearchQuery(
                topic = request.query.topic,
                tenant_id = request.query.tenant_id,
                from = request.query.from,
                to = request.query.to,
            ),
            limit = request.limit,
            cursor = null,
            reason = request.reason_code,
            actorId = actorId(authentication),
            actorIp = clientIp(httpRequest),
            correlationId = correlationId(httpRequest),
        )
        publishAdminAudit(authentication, "POST /admin/v1/audit/search", "admin_search", request.reason_code, "200", httpRequest)
        return ResponseEntity.ok(mapOf("items" to response.items, "has_more" to response.has_more))
    }

    @PostMapping("/export")
    @PreAuthorize("hasAnyAuthority('ROLE_platform.admin', 'ROLE_platform.super_admin')")
    fun export(
        @Valid @RequestBody request: AdminExportRequest,
        authentication: Authentication,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<AdminExportResponse> {
        val date = LocalDate.now(ZoneOffset.UTC).minusDays(request.days_back.toLong())
        val result = exportService.exportDay(date, request.tenant_id)
        publishAdminAudit(authentication, "POST /admin/v1/audit/export", "admin_export", request.reason_code, "200", httpRequest)
        return ResponseEntity.ok(
            AdminExportResponse(
                s3_path = result.s3Path,
                event_count = result.eventCount,
                size_bytes = result.sizeBytes,
                tenant_id = result.tenantId,
                generated_at = Instant.now(),
            ),
        )
    }

    @PostMapping("/reindex")
    @PreAuthorize("hasAnyAuthority('ROLE_audit.admin', 'ROLE_platform.super_admin')")
    fun reindex(
        authentication: Authentication,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<AdminReindexResponse> {
        // The audit log IS the index: every read API hits the partitioned
        // `audit.events` table directly. The "reindex" action here is therefore
        // a no-op acknowledgement plus a read_log scan. A real search index
        // would live in `search-service`; this endpoint is preserved per TECH
        // §10.4 so admin tooling can trigger a forced refresh of dependent
        // materialized views (none today).
        val readRows = readLog.count()
        publishAdminAudit(authentication, "POST /admin/v1/audit/reindex", "admin_reindex", null, "200", httpRequest)
        return ResponseEntity.ok(
            AdminReindexResponse(
                started_at = Instant.now(),
                status = "completed",
                note = "No external index; $readRows read_log rows scanned",
            ),
        )
    }

    private fun actorId(authentication: Authentication): UUID =
        runCatching { UUID.fromString(authentication.name) }.getOrElse { UUID(0, 0) }

    private fun clientIp(request: HttpServletRequest): String? =
        request.getHeader("X-Forwarded-For")?.substringBefore(',')?.trim() ?: request.remoteAddr

    private fun correlationId(request: HttpServletRequest): UUID =
        runCatching { UUID.fromString(request.getAttribute("correlationId")?.toString() ?: "") }.getOrNull()
            ?: UUID.randomUUID()

    private fun publishAdminAudit(
        authentication: Authentication,
        endpoint: String,
        action: String,
        reasonCode: String?,
        result: String,
        request: HttpServletRequest,
    ) {
        auditPublisher.publish(
            actorId = actorId(authentication),
            actorUsername = authentication.name,
            actorRoles = authentication.authorities.mapNotNull { it.authority },
            endpoint = endpoint,
            action = action,
            targetResource = request.requestURI,
            reasonCode = reasonCode,
            requestId = request.getHeader("X-Request-Id") ?: request.getAttribute("correlationId")?.toString(),
            traceId = request.getHeader("traceparent"),
            result = result,
            durationMs = 0L,
        )
    }
}
