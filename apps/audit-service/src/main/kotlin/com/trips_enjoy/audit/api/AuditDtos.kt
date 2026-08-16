package com.trips_enjoy.audit.api

import com.trips_enjoy.audit.domain.AuditEvent
import com.trips_enjoy.audit.domain.LitigationHold
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

/**
 * Request body for POST /v1/audit/search. Per INTEGRATION.md §1.1.
 * `tenant_id` and `reason` are required; everything else filters the query.
 */
data class AuditSearchRequest(
    val query: AuditSearchQuery,
    @field:Min(1) @field:Max(200) val limit: Int = 50,
    val cursor: String? = null,
    @field:NotBlank @field:Size(min = 8, max = 512) val reason: String,
)

data class AuditSearchQuery(
    val topic: String? = null,
    val tenant_id: String? = null,
    val subject_type: String? = null,
    val subject_id: UUID? = null,
    val from: Instant? = null,
    val to: Instant? = null,
    val correlation_id: UUID? = null,
)

data class AuditSearchResponse(
    val items: List<AuditEventSummary>,
    val next_cursor: String? = null,
    val has_more: Boolean,
)

data class AuditEventSummary(
    val id: UUID,
    val event_id: UUID,
    val event_name: String,
    val schema_version: Int,
    val occurred_at: Instant,
    val producer: String,
    val tenant_id: String,
    val correlation_id: UUID,
    val aggregate_type: String,
    val aggregate_id: UUID?,
    val subject_type: String?,
    val subject_id: UUID?,
    val topic: String,
    val retention_class: String,
    val litigation_hold: Boolean,
    val hash: String,
)

/** Full event with data + headers + prev_hash (INTEGRATION.md §1.2). */
data class AuditEventDetail(
    val id: UUID,
    val event_id: UUID,
    val event_name: String,
    val schema_version: Int,
    val occurred_at: Instant,
    val received_at: Instant,
    val producer: String,
    val tenant_id: String,
    val correlation_id: UUID,
    val causation_id: UUID?,
    val aggregate_type: String,
    val aggregate_id: UUID?,
    val subject_type: String?,
    val subject_id: UUID?,
    val data: Map<String, Any?>,
    val headers: Map<String, Any?>?,
    val topic: String,
    val partition: Int,
    val offset: Long,
    val prev_hash: String?,
    val hash: String,
    val retention_class: String,
    val litigation_hold: Boolean,
    val retention_until: Instant?,
    val created_at: Instant,
)

/** Response for GET /v1/audit/verify/{id} (INTEGRATION.md §1.3). */
data class VerifyResponse(
    val verified: Boolean,
    val verified_at: Instant,
    val chain_length: Long,
    val target_id: UUID,
    val target_hash: String,
    val recomputed_hash: String? = null,
    val mismatch_id: UUID? = null,
)

/** Request for POST /v1/audit/litigation-hold (INTEGRATION.md §1.4). */
data class LitigationHoldRequest(
    val tenant_id: String? = null,
    val subject_type: String? = null,
    val subject_id: UUID? = null,
    val topic: String? = null,
    @field:NotBlank @field:Size(min = 8, max = 512) val reason: String,
    val effective_from: Instant = Instant.now(),
    val effective_to: Instant? = null,
)

data class LitigationHoldResponse(
    val id: UUID,
    val tenant_id: String?,
    val subject_type: String?,
    val subject_id: UUID?,
    val topic: String?,
    val reason: String,
    val effective_from: Instant,
    val effective_to: Instant?,
    val created_at: Instant,
    val created_by: UUID,
)

fun LitigationHold.toResponse() = LitigationHoldResponse(
    id = id,
    tenant_id = tenantId,
    subject_type = subjectType,
    subject_id = subjectId,
    topic = topic,
    reason = reason,
    effective_from = effectiveFrom,
    effective_to = effectiveTo,
    created_at = createdAt,
    created_by = createdBy,
)

fun AuditEvent.toSummary(): AuditEventSummary = AuditEventSummary(
    id = id,
    event_id = eventId,
    event_name = eventName,
    schema_version = schemaVersion,
    occurred_at = occurredAt,
    producer = producer,
    tenant_id = tenantId,
    correlation_id = correlationId,
    aggregate_type = aggregateType,
    aggregate_id = aggregateId,
    subject_type = subjectType,
    subject_id = subjectId,
    topic = topic,
    retention_class = retentionClass,
    litigation_hold = litigationHold,
    hash = hash,
)
