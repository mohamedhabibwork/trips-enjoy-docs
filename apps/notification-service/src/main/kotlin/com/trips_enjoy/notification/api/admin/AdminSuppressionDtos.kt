package com.trips_enjoy.notification.api.admin

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import java.util.UUID

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CreateSuppressionRequest(
	val category: String,
	val reason: String,
	val expires_at: Instant? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SuppressionResponse(
	val id: UUID,
	val category: String,
	val reason: String,
	val expires_at: Instant?,
	val created_at: Instant,
	val created_by: UUID,
)