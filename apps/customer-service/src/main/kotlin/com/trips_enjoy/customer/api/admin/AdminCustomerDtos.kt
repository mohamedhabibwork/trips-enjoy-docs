package com.trips_enjoy.customer.api.admin

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class AdminOverrideKycTierRequest(
    @field:NotBlank @field:Pattern(regexp = "^tier_[0-3]$") val to_tier: String,
    @field:NotBlank @field:Size(min = 8, max = 512) val reason: String,
)

data class AdminPseudonymizeRequest(
    @field:NotBlank @field:Size(min = 8, max = 512) val reason: String,
)

data class AdminMergeRequest(
    val source_customer_id: String,
    val target_customer_id: String,
    @field:NotBlank @field:Size(min = 8, max = 512) val reason: String,
)

data class AdminMergeResponse(
    val target_customer_id: String,
    val source_customer_id: String,
    val correlation_id: String,
)
