package com.trips_enjoy.foodorder.api

import com.trips_enjoy.foodorder.domain.Order
import com.trips_enjoy.foodorder.domain.Request
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import java.time.Instant
import java.util.UUID

data class PlaceOrderRequest(
    @field:NotNull val customerId: UUID,
    @field:NotNull val restaurantId: UUID,
    val branchId: UUID? = null,
    @field:NotBlank @field:Pattern(regexp = "^(delivery|pickup|dine_in)$") val orderType: String = "delivery",
    @field:Valid val items: List<OrderItemDto> = emptyList(),
)

data class OrderItemDto(
    @field:NotNull val menuItemId: UUID,
    @field:NotBlank val name: String,
    @field:Min(1) val quantity: Int = 1,
    @field:Min(0) val unitPriceMinor: Long = 0L,
    val specialInstructions: String? = null,
)

data class PriceOrderRequest(
    @field:NotNull val requestId: UUID,
    @field:Min(1) val totalMinor: Long,
    @field:NotBlank @field:Pattern(regexp = "^[A-Z]{3}$") val currency: String = "USD",
    @field:Valid val snapshot: Map<String, Any?> = emptyMap(),
)

data class AcceptOrderRequest(
    @field:NotNull val orderId: UUID,
    val courierId: UUID? = null,
    @field:Min(1) val totalMinor: Long,
    @field:NotBlank @field:Pattern(regexp = "^[A-Z]{3}$") val currency: String = "USD",
)

data class RejectOrderRequest(@field:NotBlank val reason: String)
data class CancelOrderRequest(@field:NotBlank val reason: String)
data class StateTransitionRequest(val newState: String, val reason: String? = null)

data class AssignCourierRequest(@field:NotNull val courierId: UUID)

data class OrderRequestResponse(
    val requestId: UUID,
    val customerId: UUID,
    val restaurantId: UUID,
    val orderType: String,
    val status: String,
    val totalMinor: Long?,
    val currency: String,
    val placedAt: Instant,
)

data class OrderResponse(
    val orderId: UUID,
    val requestId: UUID,
    val customerId: UUID,
    val restaurantId: UUID,
    val courierId: UUID?,
    val orderType: String,
    val status: String,
    val totalMinor: Long,
    val currency: String,
    val placedAt: Instant?,
    val acceptedAt: Instant?,
    val preparingAt: Instant?,
    val readyAt: Instant?,
    val pickedUpAt: Instant?,
    val deliveredAt: Instant?,
    val cancelledAt: Instant?,
    val cancellationReason: String?,
)

data class CancellationFeeResponse(val feeMinor: Long)

internal fun Request.toResponse(): OrderRequestResponse = OrderRequestResponse(
    requestId = id,
    customerId = customerId,
    restaurantId = restaurantId,
    orderType = orderType,
    status = status,
    totalMinor = totalMinor,
    currency = currency,
    placedAt = placedAt,
)

internal fun Order.toResponse(): OrderResponse = OrderResponse(
    orderId = id,
    requestId = requestId,
    customerId = customerId,
    restaurantId = restaurantId,
    courierId = courierId,
    orderType = orderType,
    status = status,
    totalMinor = totalMinor,
    currency = currency,
    placedAt = placedAt,
    acceptedAt = acceptedAt,
    preparingAt = preparingAt,
    readyAt = readyAt,
    pickedUpAt = pickedUpAt,
    deliveredAt = deliveredAt,
    cancelledAt = cancelledAt,
    cancellationReason = cancellationReason,
)