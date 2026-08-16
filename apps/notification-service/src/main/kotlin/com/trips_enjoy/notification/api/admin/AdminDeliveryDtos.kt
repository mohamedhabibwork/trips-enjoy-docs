package com.trips_enjoy.notification.api.admin

import com.fasterxml.jackson.annotation.JsonInclude
import com.trips_enjoy.notification.api.DeliveryStateResponse
import java.util.UUID

@JsonInclude(JsonInclude.Include.NON_NULL)
data class DeliveryListResponse(
	val deliveries: List<DeliveryStateResponse>,
	val next_cursor: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ErasureRequest(
	val reason_code: String,
)