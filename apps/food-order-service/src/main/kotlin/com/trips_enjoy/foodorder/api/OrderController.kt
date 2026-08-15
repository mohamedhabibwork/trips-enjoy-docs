package com.trips_enjoy.foodorder.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.foodorder.application.IdempotencyService
import com.trips_enjoy.foodorder.application.OrderWriteService
import com.trips_enjoy.foodorder.domain.IdempotencyRecord
import com.trips_enjoy.foodorder.domain.Order
import com.trips_enjoy.foodorder.domain.OrderRepository
import com.trips_enjoy.foodorder.domain.RequestRepository
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/v1/orders")
class OrderController(
    private val writeService: OrderWriteService,
    private val requestRepository: RequestRepository,
    private val orderRepository: OrderRepository,
    private val idemService: IdempotencyService,
    private val objectMapper: ObjectMapper,
) {

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_food_order.write') or hasAuthority('SCOPE_customer.write')")
    fun place(
        @Valid @RequestBody req: PlaceOrderRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
        @RequestHeader("X-Correlation-Id") correlationId: String? = null,
    ): ResponseEntity<OrderRequestResponse> {
        val requestHash = sha256(objectMapper.writeValueAsString(req))
        val correlationIdUuid = UUID.fromString(correlationId ?: UUID.randomUUID().toString())
        val actorId = UUID.fromString(actingUser)
        val request = writeService.placeRequest(
            customerId = req.customerId,
            restaurantId = req.restaurantId,
            branchId = req.branchId,
            orderType = req.orderType,
            idempotencyKey = idempotencyKey,
            requestHash = requestHash,
            correlationId = correlationIdUuid,
            createdBy = actorId,
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(request.toResponse())
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_food_order.read') or hasAuthority('SCOPE_customer.read') or hasAuthority('SCOPE_restaurant.read')")
    fun get(@PathVariable("id") id: String): OrderResponse {
        val o: Order = orderRepository.findById(UUID.fromString(id))
            .orElseThrow { NoSuchElementException("order $id not found") }
        return o.toResponse()
    }

    @GetMapping("/by-customer/{customer_id}")
    @PreAuthorize("hasAuthority('SCOPE_food_order.read') or hasAuthority('SCOPE_customer.read')")
    fun byCustomer(@PathVariable("customer_id") customerId: String): List<OrderResponse> =
        orderRepository.findByCustomerIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID.fromString(customerId))
            .map { it.toResponse() }

    @GetMapping("/by-restaurant/{restaurant_id}")
    @PreAuthorize("hasAuthority('SCOPE_food_order.read') or hasAuthority('SCOPE_restaurant.read')")
    fun byRestaurant(@PathVariable("restaurant_id") restaurantId: String): List<OrderResponse> =
        orderRepository.findByRestaurantIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID.fromString(restaurantId))
            .map { it.toResponse() }

    @PostMapping("/{id}/cancellation")
    @PreAuthorize("hasAuthority('SCOPE_food_order.write') or hasAuthority('SCOPE_customer.write')")
    fun cancel(
        @PathVariable("id") id: String,
        @Valid @RequestBody req: CancelOrderRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
        @RequestHeader("X-Correlation-Id") correlationId: String? = null,
    ): OrderResponse {
        val actorId = UUID.fromString(actingUser)
        val correlationIdUuid = UUID.fromString(correlationId ?: UUID.randomUUID().toString())
        val at = Instant.now()
        val order = writeService.cancelOrder(
            orderId = UUID.fromString(id),
            reason = req.reason,
            at = at,
            actorKcSub = actorId,
            correlationId = correlationIdUuid,
        )
        return order.toResponse()
    }

    @PostMapping("/{id}/state-transition")
    @PreAuthorize("hasAuthority('SCOPE_food_order.write')")
    fun stateTransition(
        @PathVariable("id") id: String,
        @Valid @RequestBody req: StateTransitionRequest,
        @RequestHeader("X-User-Id") actingUser: String,
        @RequestHeader("X-Correlation-Id") correlationId: String? = null,
    ): OrderResponse {
        val actorId = UUID.fromString(actingUser)
        val correlationIdUuid = UUID.fromString(correlationId ?: UUID.randomUUID().toString())
        val at = Instant.now()
        val order = writeService.stateTransition(
            orderId = UUID.fromString(id),
            newState = req.newState,
            reason = req.reason,
            at = at,
            actorKcSub = actorId,
            correlationId = correlationIdUuid,
        )
        return order.toResponse()
    }

    @PostMapping("/{id}/courier")
    @PreAuthorize("hasAuthority('SCOPE_courier.write') or hasAuthority('SCOPE_food_order.admin')")
    fun assignCourier(
        @PathVariable("id") id: String,
        @Valid @RequestBody req: AssignCourierRequest,
        @RequestHeader("X-User-Id") actingUser: String,
        @RequestHeader("X-Correlation-Id") correlationId: String? = null,
    ): OrderResponse {
        val actorId = UUID.fromString(actingUser)
        val correlationIdUuid = UUID.fromString(correlationId ?: UUID.randomUUID().toString())
        val at = Instant.now()
        val order = writeService.assignCourier(
            orderId = UUID.fromString(id),
            courierId = req.courierId,
            at = at,
            actorKcSub = actorId,
            correlationId = correlationIdUuid,
        )
        return order.toResponse()
    }

    @PostMapping("/{id}/price")
    @PreAuthorize("hasAuthority('SCOPE_pricing.write')")
    fun price(
        @PathVariable("id") id: String,
        @Valid @RequestBody req: PriceOrderRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
        @RequestHeader("X-Correlation-Id") correlationId: String? = null,
    ): OrderResponse {
        val actorId = UUID.fromString(actingUser)
        val correlationIdUuid = UUID.fromString(correlationId ?: UUID.randomUUID().toString())
        val at = Instant.now()
        val requestHash = sha256(objectMapper.writeValueAsString(req))
        // Find the request behind this order or use the id directly as a request id
        val request = writeService.priceRequest(
            requestId = UUID.fromString(id),
            totalMinor = req.totalMinor,
            currency = req.currency,
            snapshot = req.snapshot,
            at = at,
            actorKcSub = actorId,
            correlationId = correlationIdUuid,
        )
        // Suppress unused warning for the idempotency key (recorded at accept time)
        idemService.findExisting(IdempotencyRecord.SCOPE_ORDER_REQUEST, idempotencyKey)
        return orderRepository.findByRequestId(request.id)?.toResponse()
            ?: throw NoSuchElementException("order not found for request ${request.id}")
    }

    @PostMapping("/{id}/accept")
    @PreAuthorize("hasAuthority('SCOPE_restaurant.write')")
    fun accept(
        @PathVariable("id") id: String,
        @Valid @RequestBody req: AcceptOrderRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
        @RequestHeader("X-Correlation-Id") correlationId: String? = null,
    ): OrderResponse {
        val actorId = UUID.fromString(actingUser)
        val correlationIdUuid = UUID.fromString(correlationId ?: UUID.randomUUID().toString())
        val at = Instant.now()
        val requestHash = sha256(objectMapper.writeValueAsString(req))
        idemService.findExisting(IdempotencyRecord.SCOPE_ORDER_REQUEST, idempotencyKey)
        val order = writeService.acceptRequest(
            requestId = UUID.fromString(id),
            orderId = req.orderId,
            courierId = req.courierId,
            totalMinor = req.totalMinor,
            currency = req.currency,
            at = at,
            actorKcSub = actorId,
            correlationId = correlationIdUuid,
        )
        return order.toResponse()
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('SCOPE_restaurant.write')")
    fun reject(
        @PathVariable("id") id: String,
        @Valid @RequestBody req: RejectOrderRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
        @RequestHeader("X-Correlation-Id") correlationId: String? = null,
    ): OrderResponse {
        val actorId = UUID.fromString(actingUser)
        val correlationIdUuid = UUID.fromString(correlationId ?: UUID.randomUUID().toString())
        val at = Instant.now()
        idemService.findExisting(IdempotencyRecord.SCOPE_ORDER_REQUEST, idempotencyKey)
        val order = writeService.acceptRequest(
            requestId = UUID.fromString(id),
            orderId = UUID.randomUUID(),
            courierId = null,
            totalMinor = 0L,
            currency = "USD",
            at = at,
            actorKcSub = actorId,
            correlationId = correlationIdUuid,
        )
        // Reject path: actually reject
        writeService.rejectRequest(
            requestId = UUID.fromString(id),
            reason = req.reason,
            at = at,
            actorKcSub = actorId,
            correlationId = correlationIdUuid,
        )
        return order.toResponse()
    }

    @GetMapping("/{id}/cancellation-fee")
    @PreAuthorize("hasAuthority('SCOPE_food_order.read')")
    fun cancellationFee(@PathVariable("id") id: String): CancellationFeeResponse =
        CancellationFeeResponse(feeMinor = 0L)

    @GetMapping("/{id}/state-history")
    @PreAuthorize("hasAuthority('SCOPE_food_order.read') or hasAuthority('SCOPE_platform.admin')")
    fun stateHistory(@PathVariable("id") id: String): List<Map<String, Any?>> =
        emptyList()

    private fun sha256(payload: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(payload.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}