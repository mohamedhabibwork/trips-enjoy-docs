package com.trips_enjoy.foodorder.conductor

import com.trips_enjoy.foodorder.application.OrderWriteService
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The Conductor workflow workers for food-order-service. Per
 * [ADR-0018](docs/architecture/adrs/0018-workflow-engine-conductor.md)
 * food-order-service owns 4 of the 17 workflow IDs:
 *   - wf.food_order.dispatch.v1   (this file — the dispatch saga)
 *   - wf.food_order.accept.v1     (the restaurant accept saga)
 *   - wf.food_order.complete.v1   (the delivery completion saga)
 *   - wf.food_order.cancel.v1     (the cancellation saga)
 *
 * Each worker is a thin wrapper that translates a Conductor task input
 * map to a call into the OrderWriteService application layer.
 */
@Component
class FoodOrderConductorWorkers(
    private val orderWriteService: OrderWriteService,
) {

    fun dispatch(input: Map<String, Any?>): Map<String, Any?> {
        val request = orderWriteService.placeRequest(
            customerId = UUID.fromString(input["customer_id"] as String),
            restaurantId = UUID.fromString(input["restaurant_id"] as String),
            branchId = (input["branch_id"] as? String)?.let(UUID::fromString),
            orderType = (input["order_type"] as? String) ?: "delivery",
            idempotencyKey = input["idempotency_key"] as String,
            requestHash = (input["request_hash"] as? String) ?: sha256(input["idempotency_key"] as String),
            correlationId = UUID.fromString(input["correlation_id"] as String),
            createdBy = UUID.fromString(input["acting_user_id"] as String),
        )
        return mapOf(
            "request_id" to request.id.toString(),
            "customer_id" to request.customerId.toString(),
            "restaurant_id" to request.restaurantId.toString(),
            "status" to request.status,
        )
    }

    fun accept(input: Map<String, Any?>): Map<String, Any?> {
        val order = orderWriteService.acceptRequest(
            requestId = UUID.fromString(input["request_id"] as String),
            orderId = UUID.fromString(input["order_id"] as String),
            courierId = (input["courier_id"] as? String)?.let(UUID::fromString),
            totalMinor = (input["total_minor"] as Number).toLong(),
            currency = input["currency"] as String,
            at = java.time.Instant.now(),
            actorKcSub = UUID.fromString(input["acting_user_id"] as String),
            correlationId = UUID.fromString(input["correlation_id"] as String),
        )
        return mapOf(
            "order_id" to order.id.toString(),
            "status" to order.status,
        )
    }

    fun complete(input: Map<String, Any?>): Map<String, Any?> {
        val order = orderWriteService.stateTransition(
            orderId = UUID.fromString(input["order_id"] as String),
            newState = "delivered",
            reason = input["reason"] as? String,
            at = java.time.Instant.now(),
            actorKcSub = UUID.fromString(input["acting_user_id"] as String),
            correlationId = UUID.fromString(input["correlation_id"] as String),
        )
        return mapOf(
            "order_id" to order.id.toString(),
            "status" to order.status,
        )
    }

    fun cancel(input: Map<String, Any?>): Map<String, Any?> {
        val order = orderWriteService.cancelOrder(
            orderId = UUID.fromString(input["order_id"] as String),
            reason = input["reason"] as String,
            at = java.time.Instant.now(),
            actorKcSub = UUID.fromString(input["acting_user_id"] as String),
            correlationId = UUID.fromString(input["correlation_id"] as String),
        )
        return mapOf(
            "order_id" to order.id.toString(),
            "status" to order.status,
        )
    }

    private fun sha256(payload: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(payload.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}