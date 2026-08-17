package com.trips_enjoy.audit.api.admin

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class AdminAuditSearchRequest(
    val query: AdminAuditSearchQuery,
    @field:Min(1) @field:Max(200) val limit: Int = 50,
    @field:NotBlank @field:Size(min = 8, max = 512) val reason_code: String,
)

data class AdminAuditSearchQuery(
    val topic: String? = null,
    val actor_id: UUID? = null,
    val endpoint: String? = null,
    val tenant_id: String? = null,
    val from: Instant? = null,
    val to: Instant? = null,
)

data class AdminExportRequest(
    @field:Min(0) val days_back: Int = 1,
    @field:NotBlank val tenant_id: String,
    @field:NotBlank @field:Size(min = 8, max = 512) val reason_code: String,
)

data class AdminExportResponse(
    val s3_path: String,
    val event_count: Long,
    val size_bytes: Long,
    val tenant_id: String,
    val generated_at: Instant,
)

data class AdminReindexResponse(
    val started_at: Instant,
    val status: String,
    val note: String,
)
