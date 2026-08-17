package com.trips_enjoy.foodorder.application

import com.trips_enjoy.foodorder.domain.IdempotencyRecord
import com.trips_enjoy.foodorder.domain.Order
import com.trips_enjoy.foodorder.domain.OrderItem
import com.trips_enjoy.foodorder.domain.OrderRepository
import com.trips_enjoy.foodorder.domain.OrderStateHistory
import com.trips_enjoy.foodorder.domain.OrderStateHistoryRepository
import com.trips_enjoy.foodorder.domain.OutboxEvent
import com.trips_enjoy.foodorder.domain.OutboxEventRepository
import com.trips_enjoy.foodorder.domain.Request
import com.trips_enjoy.foodorder.domain.RequestRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * The food-order write-service — orchestrates the food delivery saga.
 *
 * Lifecycle:
 *   Request: draft → priced → placed → accepted | rejected → ...
 *   Order:   pending → accepted → preparing → ready → picked_up → delivered
 *
 * Every mutation writes an `order_state_history` row + publishes via
 * outbox.
 *
 * Phase C (platform DRY): audit fields on the aggregates
 * (`Request`, `Order`) are inherited from `BaseEntity`. `id` is
 * assigned by the `BaseEntity` UUIDv7 generator, `createdBy` /
 * `updatedBy` are populated by `PlatformAuditorAware` from the JWT
 * `sub`, and `version` is the optimistic-lock counter (formerly
 * `rowVersion`). The explicit `createdBy: UUID` parameter that used
 * to be threaded into constructors is therefore dropped — the JWT
 * `sub` is the canonical author and is captured by the auditor.
 *
 * The `actorKcSub: UUID` parameters on the public API still flow
 * through because they are used as `actor_kc_sub` on the
 * `order_state_history` row (a domain-level attribution, NOT the
 * audit column) and as `created_by` on the `outbox` row (the
 * service-local column that was preserved during Phase B).
 */
@Service
class OrderWriteService(
    private val requestRepository: RequestRepository,
    private val orderRepository: OrderRepository,
    private val stateHistoryRepository: OrderStateHistoryRepository,
    private val outboxRepository: OutboxEventRepository,
    private val idemService: IdempotencyService,
) {

    @Transactional
    fun placeRequest(
        customerId: UUID,
        restaurantId: UUID,
        branchId: UUID?,
        orderType: String,
        idempotencyKey: String,
        requestHash: String,
        correlationId: UUID,
        actorKcSub: UUID,
    ): Request {
        val existing = idemService.findExisting(IdempotencyRecord.SCOPE_ORDER_REQUEST, idempotencyKey)
        if (existing != null) {
            require(existing.requestHash == requestHash) { "idempotency key body mismatch" }
            val cachedId = UUID.fromString(existing.responseBody?.get("request_id") as String? ?: "")
            val cached: Request = requestRepository.findById(cachedId)
                .orElseThrow { error("idempotency record refers to missing request") }
            return cached
        }
        val now = Instant.now()
        val request = Request(
            customerId = customerId,
            restaurantId = restaurantId,
            branchId = branchId,
            orderType = orderType,
            idempotencyKey = idempotencyKey,
            correlationId = correlationId,
        )
        requestRepository.save(request)
        val requestId = requireNotNull(request.id) { "Request.id must be assigned after save" }
        writeHistory(requestId, "request", null, Request.STATUS_DRAFT, actorKcSub, "customer", null, correlationId, now)
        idemService.record(
            IdempotencyRecord.SCOPE_ORDER_REQUEST,
            idempotencyKey,
            requestHash,
            201,
            mapOf("request_id" to requestId.toString()),
            actorKcSub,
            now,
        )
        emitEvent(requestId, "request", "food.order.placed.v1", correlationId, actorKcSub, mapOf(
            "request_id" to requestId.toString(),
            "customer_id" to customerId.toString(),
            "restaurant_id" to restaurantId.toString(),
        ))
        return request
    }

    @Transactional
    fun priceRequest(
        requestId: UUID,
        totalMinor: Long,
        currency: String,
        snapshot: Map<String, Any?>,
        at: Instant,
        actorKcSub: UUID,
        correlationId: UUID,
    ): Request {
        val request = requestRepository.findById(requestId).orElseThrow()
        val fromState = request.status
        request.price(totalMinor, snapshot, currency, at)
        writeHistory(request.id!!, "request", fromState, request.status, actorKcSub, "system", null, correlationId, at)
        emitEvent(request.id!!, "request", "food.order.priced.v1", correlationId, actorKcSub, mapOf(
            "request_id" to requestId.toString(),
            "total_minor" to totalMinor.toString(),
        ))
        return request
    }

    @Transactional
    fun acceptRequest(
        requestId: UUID,
        orderId: UUID,
        courierId: UUID?,
        totalMinor: Long,
        currency: String,
        at: Instant,
        actorKcSub: UUID,
        correlationId: UUID,
    ): Order {
        val request = requestRepository.findById(requestId).orElseThrow()
        val fromState = request.status
        request.accept(at)
        writeHistory(request.id!!, "request", fromState, request.status, actorKcSub, "restaurant", null, correlationId, at)
        emitEvent(request.id!!, "request", "food.order.accepted.v1", correlationId, actorKcSub, mapOf(
            "request_id" to requestId.toString(),
            "order_id" to orderId.toString(),
        ))

        // Promote to Order.
        val order = Order(
            requestId = requestId,
            customerId = request.customerId,
            restaurantId = request.restaurantId,
            branchId = request.branchId,
            courierId = courierId,
            orderType = request.orderType,
            totalMinor = totalMinor,
            currency = currency,
            correlationId = correlationId,
            idempotencyKey = request.idempotencyKey,
            placedAt = request.placedAt,
        )
        // Phase C: assign the deterministic id supplied by the restaurant
        // (the requestId is the canonical Request PK but the Order PK is
        // independently issued so downstream consumers can reference it
        // before the Order row is saved).
        order.id = orderId
        order.accept(at)
        orderRepository.save(order)
        writeHistory(order.id!!, "order", null, Order.STATUS_ACCEPTED, actorKcSub, "restaurant", null, correlationId, at)
        emitEvent(order.id!!, "order", "food.order.accepted.v1", correlationId, actorKcSub, mapOf(
            "order_id" to orderId.toString(),
        ))
        return order
    }

    @Transactional
    fun rejectRequest(
        requestId: UUID,
        reason: String,
        at: Instant,
        actorKcSub: UUID,
        correlationId: UUID,
    ): Request {
        val request = requestRepository.findById(requestId).orElseThrow()
        val fromState = request.status
        request.reject(reason, at)
        writeHistory(request.id!!, "request", fromState, request.status, actorKcSub, "restaurant", reason, correlationId, at)
        emitEvent(request.id!!, "request", "food.order.rejected.v1", correlationId, actorKcSub, mapOf(
            "request_id" to requestId.toString(),
            "reason" to reason,
        ))
        return request
    }

    @Transactional
    fun cancelRequest(
        requestId: UUID,
        reason: String,
        at: Instant,
        actorKcSub: UUID,
        correlationId: UUID,
    ): Request {
        val request = requestRepository.findById(requestId).orElseThrow()
        val fromState = request.status
        request.cancel(reason, at)
        writeHistory(request.id!!, "request", fromState, request.status, actorKcSub, "customer", reason, correlationId, at)
        emitEvent(request.id!!, "request", "food.order.cancelled.v1", correlationId, actorKcSub, mapOf(
            "request_id" to requestId.toString(),
            "reason" to reason,
        ))
        return request
    }

    @Transactional
    fun startPreparing(orderId: UUID, at: Instant, actorKcSub: UUID, correlationId: UUID): Order {
        val order = orderRepository.findById(orderId).orElseThrow()
        val fromState = order.status
        order.startPreparing(at)
        writeHistory(order.id!!, "order", fromState, order.status, actorKcSub, "restaurant", null, correlationId, at)
        emitEvent(order.id!!, "order", "food.order.preparing.v1", correlationId, actorKcSub, mapOf("order_id" to orderId.toString()))
        return order
    }

    @Transactional
    fun markReady(orderId: UUID, at: Instant, actorKcSub: UUID, correlationId: UUID): Order {
        val order = orderRepository.findById(orderId).orElseThrow()
        val fromState = order.status
        order.markReady(at)
        writeHistory(order.id!!, "order", fromState, order.status, actorKcSub, "restaurant", null, correlationId, at)
        emitEvent(order.id!!, "order", "food.order.ready.v1", correlationId, actorKcSub, mapOf("order_id" to orderId.toString()))
        return order
    }

    @Transactional
    fun assignCourier(orderId: UUID, courierId: UUID, at: Instant, actorKcSub: UUID, correlationId: UUID): Order {
        val order = orderRepository.findById(orderId).orElseThrow()
        order.assignCourier(courierId, at)
        writeHistory(order.id!!, "order", order.status, order.status, actorKcSub, "dispatch", "courier_assigned", correlationId, at)
        emitEvent(order.id!!, "order", "food.order.courier_assigned.v1", correlationId, actorKcSub, mapOf(
            "order_id" to orderId.toString(),
            "courier_id" to courierId.toString(),
        ))
        return order
    }

    @Transactional
    fun markPickedUp(orderId: UUID, at: Instant, actorKcSub: UUID, correlationId: UUID): Order {
        val order = orderRepository.findById(orderId).orElseThrow()
        val fromState = order.status
        order.markPickedUp(at)
        writeHistory(order.id!!, "order", fromState, order.status, actorKcSub, "courier", null, correlationId, at)
        emitEvent(order.id!!, "order", "food.order.picked_up.v1", correlationId, actorKcSub, mapOf("order_id" to orderId.toString()))
        return order
    }

    @Transactional
    fun markDelivered(orderId: UUID, at: Instant, actorKcSub: UUID, correlationId: UUID): Order {
        val order = orderRepository.findById(orderId).orElseThrow()
        val fromState = order.status
        order.markDelivered(at)
        writeHistory(order.id!!, "order", fromState, order.status, actorKcSub, "courier", null, correlationId, at)
        emitEvent(order.id!!, "order", "food.order.delivered.v1", correlationId, actorKcSub, mapOf("order_id" to orderId.toString()))
        return order
    }

    @Transactional
    fun cancelOrder(orderId: UUID, reason: String, at: Instant, actorKcSub: UUID, correlationId: UUID): Order {
        val order = orderRepository.findById(orderId).orElseThrow()
        val fromState = order.status
        order.cancel(reason, at)
        writeHistory(order.id!!, "order", fromState, order.status, actorKcSub, "customer", reason, correlationId, at)
        emitEvent(order.id!!, "order", "food.order.cancelled.v1", correlationId, actorKcSub, mapOf(
            "order_id" to orderId.toString(),
            "reason" to reason,
        ))
        return order
    }

    @Transactional
    fun stateTransition(
        orderId: UUID,
        newState: String,
        reason: String?,
        at: Instant,
        actorKcSub: UUID,
        correlationId: UUID,
    ): Order {
        val order = orderRepository.findById(orderId).orElseThrow()
        val fromState = order.status
        when (newState) {
            Order.STATUS_PREPARING -> order.startPreparing(at)
            Order.STATUS_READY -> order.markReady(at)
            Order.STATUS_PICKED_UP -> order.markPickedUp(at)
            Order.STATUS_DELIVERED -> order.markDelivered(at)
            Order.STATUS_CANCELLED -> order.cancel(reason ?: "cancelled", at)
            Order.STATUS_NO_SHOW -> order.markNoShow(at, reason ?: "no_show")
            else -> throw IllegalArgumentException("unknown new state $newState")
        }
        writeHistory(order.id!!, "order", fromState, order.status, actorKcSub, "admin", reason, correlationId, at)
        emitEvent(order.id!!, "order", "food.order.state_transitioned.v1", correlationId, actorKcSub, mapOf(
            "order_id" to orderId.toString(),
            "from_state" to fromState,
            "to_state" to order.status,
        ))
        return order
    }

    private fun writeHistory(
        subjectId: UUID,
        subjectKind: String,
        fromState: String?,
        toState: String,
        actorKcSub: UUID,
        actorKind: String,
        reason: String?,
        correlationId: UUID,
        at: Instant,
    ) {
        val history = OrderStateHistory(
            id = UUID.randomUUID(),
            subjectId = subjectId,
            subjectKind = subjectKind,
            fromState = fromState,
            toState = toState,
            actorKcSub = actorKcSub,
            actorKind = actorKind,
            reason = reason,
            correlationId = correlationId,
            occurredAt = at,
        )
        stateHistoryRepository.save(history)
    }

    private fun emitEvent(
        subjectId: UUID,
        subjectKind: String,
        eventType: String,
        correlationId: UUID,
        createdBy: UUID,
        payload: Map<String, Any?>,
    ) {
        outboxRepository.save(
            OutboxEvent(
                id = UUID.randomUUID(),
                aggregateType = subjectKind,
                aggregateId = subjectId,
                eventType = eventType,
                topic = eventType,
                payload = payload,
                correlationId = correlationId,
                createdBy = createdBy,
            ),
        )
    }
}