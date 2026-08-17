package com.trips_enjoy.configuration.api

import com.fasterxml.jackson.databind.JsonNode
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

// ---------------------------------------------------------------------------
// Read endpoints (FR-001 / FR-013 / FR-014)
// ---------------------------------------------------------------------------

data class ConfigurationValueResponse(
    val key: String,
    val value: JsonNode,
    val matched_scope_type: String,
    val matched_scope_id: String?,
    val version: Long,
    val schema_version: Int,
    val resolved_at: Instant,
    val correlation_id: UUID,
)

data class SnapshotResponse(
    val tenant_id: String,
    val as_of: Instant,
    val values: Map<String, ConfigurationValueResponse>,
)

data class ChannelSubsetResponse(
    val channel: String,
    val as_of: Instant,
    val values: Map<String, JsonNode>,
)

// ---------------------------------------------------------------------------
// Write endpoints (FR-005 / FR-006 / FR-007 / FR-019)
// ---------------------------------------------------------------------------

data class CreateConfigurationRequest(
    @field:NotBlank
    @field:Pattern(regexp = "^[a-z][a-z0-9_.\\-]{1,127}$")
    val key: String,
    val schema: JsonNode,
    val value: JsonNode,
    @field:NotBlank val scope_type: String,
    val scope_id: String? = null,
    @field:NotBlank @field:Size(min = 8, max = 512) val reason: String,
)

data class PutVersionRequest(
    val value: JsonNode,
    @field:NotBlank val scope_type: String,
    val scope_id: String? = null,
    val cohort: JsonNode? = null,
    val effective_from: Instant? = null,
    val effective_to: Instant? = null,
    val expected_current_version: Long,
    @field:NotBlank @field:Size(min = 8, max = 512) val reason: String,
)

data class RollbackRequest(
    val to_version: Long,
    @field:NotBlank @field:Size(min = 8, max = 512) val reason: String,
)

data class DeprecateRequest(
    @field:NotBlank @field:Size(min = 8, max = 512) val reason: String,
    val replacement_key: String? = null,
)

// ---------------------------------------------------------------------------
// Write response (UNIFIED across PUT/POST/rollback per INTEGRATION.md §1.2)
// ---------------------------------------------------------------------------

data class WriteImpact(
    val consumers_reloading: List<String>,
)

data class WriteResponse(
    val document_id: UUID,
    val key: String,
    val version: Long,
    val value: JsonNode,
    val matched_scope_type: String,
    val matched_scope_id: String?,
    val impact: WriteImpact,
    val correlation_id: UUID,
)

// ---------------------------------------------------------------------------
// History endpoints (FR-008)
// ---------------------------------------------------------------------------

data class HistoryItemResponse(
    val version: Long,
    val scope_type: String,
    val scope_id: String?,
    val value: JsonNode?,
    val actor_id: UUID,
    val reason: String,
    val created_at: Instant,
    val superseded_at: Instant?,
)

data class HistoryResponse(
    val items: List<HistoryItemResponse>,
    val next_cursor: String?,
    val has_more: Boolean,
)

data class SpecificVersionResponse(
    val key: String,
    val version: Long,
    val value: JsonNode?,
    val scope_type: String,
    val scope_id: String?,
    val actor_id: UUID,
    val reason: String,
    val created_at: Instant,
    val correlation_id: UUID,
)

// ---------------------------------------------------------------------------
// Long-poll (FR-009)
// ---------------------------------------------------------------------------

data class LongPollUpdate(
    val key: String,
    val version: Long,
    val value: JsonNode,
    val changed_at: Instant,
)

data class LongPollResponse(
    val updates: List<LongPollUpdate>,
    val next_since_version: Long?,
)

// ---------------------------------------------------------------------------
// Admin endpoints (TECH.md §10.4)
// ---------------------------------------------------------------------------

data class AdminRollbackRequest(
    val to_version: Long,
    @field:NotBlank @field:Size(min = 8, max = 512) val reason: String,
)

data class AdminBulkPublishRequest(
    val keys: List<String>,
    val reason: String,
)
