package com.trips_enjoy.platform.audit

import java.time.Instant
import java.util.UUID

/**
 * Audit event envelope. Two topics use this shape:
 *   - `audit.api.request.v1` emitted on every authenticated HTTP request
 *   - `audit.admin.<service>.v1` emitted on every `/admin/v1/...` call
 *
 * Sourced from `docs/shared/CONVENTIONS.md` section 3.
 */
data class AuditEvent(
    val auditId: UUID,
    val timestamp: Instant,
    val actorId: String?,
    val actorUsername: String?,
    val actorType: String,
    val service: String,
    val endpoint: String,
    val targetResource: String? = null,
    val result: String,
    val durationMs: Long,
    val requestId: String?,
    val traceId: String? = null,
    val spanId: String? = null,
    val clientIp: String? = null,
    val userAgent: String? = null,
    val roles: List<String> = emptyList(),
    val action: String? = null,
    val reasonCode: String? = null,
)
