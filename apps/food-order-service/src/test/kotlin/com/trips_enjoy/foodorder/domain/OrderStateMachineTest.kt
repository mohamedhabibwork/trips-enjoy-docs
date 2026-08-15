package com.trips_enjoy.foodorder.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for the food-order-service state machines.
 * Covers:
 *   - Request: price / place / accept / reject / cancel
 *   - Order: accept / startPreparing / markReady / assignCourier /
 *     markPickedUp / markDelivered / cancel / markNoShow
 *   - OrderItem: quantity + price invariants
 *   - IdempotencyRecord: scope + idem_key + request_hash validation
 *   - OutboxEvent: mark_published / mark_failed lifecycle
 *
 * Single-UUID PKs everywhere (NOT composite @IdClass) per the canonical
 * lift-forward pattern that avoided the admin-service blocker.
 */
class OrderStateMachineTest {

    private val now: Instant = Instant.parse("2026-08-15T12:00:00Z")
    private val sys: UUID = UUID.randomUUID()
    private val courier: UUID = UUID.randomUUID()
    private val branch: UUID = UUID.randomUUID()

    private fun newRequest(): Request = Request(
        id = UUID.randomUUID(),
        customerId = UUID.randomUUID(),
        restaurantId = UUID.randomUUID(),
        branchId = branch,
        orderType = Request.ORDER_TYPE_DELIVERY,
        createdBy = sys,
    )

    private fun newOrder(): Order = Order(
        id = UUID.randomUUID(),
        requestId = UUID.randomUUID(),
        customerId = UUID.randomUUID(),
        restaurantId = UUID.randomUUID(),
        branchId = branch,
        orderType = Request.ORDER_TYPE_DELIVERY,
        totalMinor = 2350L,
        createdBy = sys,
    )

    // ---------- Request ----------

    @Test
    fun `request price moves draft to priced`() {
        val r = newRequest()
        r.price(2350L, mapOf("tax_minor" to 0), "USD", now)
        assertEquals(Request.STATUS_PRICED, r.status)
        assertEquals(2350L, r.totalMinor)
    }

    @Test
    fun `request price rejects non-positive total`() {
        val r = newRequest()
        assertThrows(IllegalStateException::class.java) {
            r.price(0L, mapOf("tax_minor" to 0), "USD", now)
        }
    }

    @Test
    fun `request place moves draft or priced to placed`() {
        val r = newRequest()
        r.place(now)
        assertEquals(Request.STATUS_PLACED, r.status)
    }

    @Test
    fun `request reject only valid from placed`() {
        val r = newRequest()
        r.place(now)
        r.reject("kitchen_overloaded", now.plusSeconds(60))
        assertEquals(Request.STATUS_REJECTED, r.status)
    }

    @Test
    fun `request cancel rejects terminal states`() {
        val r = newRequest()
        r.place(now)
        r.reject("test", now.plusSeconds(60))
        // Now in STATUS_REJECTED (terminal). Cancel should be rejected.
        assertThrows(IllegalStateException::class.java) {
            r.cancel("customer_no_longer_interested", now.plusSeconds(120))
        }
    }

    @Test
    fun `request order types validated at construction`() {
        for (type in Request.VALID_ORDER_TYPES) {
            Request(
                id = UUID.randomUUID(),
                customerId = UUID.randomUUID(),
                restaurantId = UUID.randomUUID(),
                orderType = type,
                createdBy = sys,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            Request(
                id = UUID.randomUUID(),
                customerId = UUID.randomUUID(),
                restaurantId = UUID.randomUUID(),
                orderType = "drone",
                createdBy = sys,
            )
        }
    }

    @Test
    fun `request status values validated at construction`() {
        for (s in Request.VALID_STATUSES) {
            Request(
                id = UUID.randomUUID(),
                customerId = UUID.randomUUID(),
                restaurantId = UUID.randomUUID(),
                status = s,
                createdBy = sys,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            Request(
                id = UUID.randomUUID(),
                customerId = UUID.randomUUID(),
                restaurantId = UUID.randomUUID(),
                status = "archived",
                createdBy = sys,
            )
        }
    }

    // ---------- Order ----------

    @Test
    fun `order accept moves pending to accepted`() {
        val o = newOrder()
        o.accept(now)
        assertEquals(Order.STATUS_ACCEPTED, o.status)
        assertNotNull(o.acceptedAt)
    }

    @Test
    fun `order accept rejects non-pending state`() {
        val o = newOrder()
        o.accept(now)
        assertThrows(IllegalStateException::class.java) {
            o.accept(now.plusSeconds(60))
        }
    }

    @Test
    fun `order startPreparing moves accepted to preparing`() {
        val o = newOrder()
        o.accept(now)
        o.startPreparing(now.plusSeconds(60))
        assertEquals(Order.STATUS_PREPARING, o.status)
    }

    @Test
    fun `order markReady moves preparing to ready`() {
        val o = newOrder()
        o.accept(now)
        o.startPreparing(now.plusSeconds(60))
        o.markReady(now.plusSeconds(600))
        assertEquals(Order.STATUS_READY, o.status)
    }

    @Test
    fun `order assignCourier requires ready state`() {
        val o = newOrder()
        o.accept(now)
        // Not in READY yet — assignCourier should fail.
        assertThrows(IllegalStateException::class.java) {
            o.assignCourier(courier, now.plusSeconds(60))
        }
    }

    @Test
    fun `order assignCourier moves ready state and sets courier`() {
        val o = newOrder()
        o.accept(now)
        o.startPreparing(now.plusSeconds(60))
        o.markReady(now.plusSeconds(600))
        o.assignCourier(courier, now.plusSeconds(700))
        assertEquals(courier, o.courierId)
    }

    @Test
    fun `order assignCourier double-assign rejected`() {
        val o = newOrder()
        o.accept(now)
        o.startPreparing(now.plusSeconds(60))
        o.markReady(now.plusSeconds(600))
        o.assignCourier(courier, now.plusSeconds(700))
        assertThrows(IllegalStateException::class.java) {
            o.assignCourier(UUID.randomUUID(), now.plusSeconds(800))
        }
    }

    @Test
    fun `order markPickedUp requires courier assigned`() {
        val o = newOrder()
        o.accept(now)
        o.startPreparing(now.plusSeconds(60))
        o.markReady(now.plusSeconds(600))
        assertThrows(IllegalArgumentException::class.java) {
            o.markPickedUp(now.plusSeconds(700))
        }
    }

    @Test
    fun `order full happy path pending → accepted → preparing → ready → picked_up → delivered`() {
        val o = newOrder()
        o.accept(now)
        o.startPreparing(now.plusSeconds(60))
        o.markReady(now.plusSeconds(600))
        o.assignCourier(courier, now.plusSeconds(700))
        o.markPickedUp(now.plusSeconds(800))
        o.markDelivered(now.plusSeconds(1800))
        assertEquals(Order.STATUS_DELIVERED, o.status)
    }

    @Test
    fun `order cancel rejects delivered`() {
        val o = newOrder()
        o.accept(now); o.startPreparing(now.plusSeconds(60)); o.markReady(now.plusSeconds(600))
        o.assignCourier(courier, now.plusSeconds(700)); o.markPickedUp(now.plusSeconds(800))
        o.markDelivered(now.plusSeconds(1800))
        assertThrows(IllegalStateException::class.java) {
            o.cancel("customer_changed_mind", now.plusSeconds(2000))
        }
    }

    @Test
    fun `order markNoShow requires picked_up state`() {
        val o = newOrder()
        assertThrows(IllegalStateException::class.java) {
            o.markNoShow(now, "test")
        }
    }

    // ---------- OrderItem ----------

    @Test
    fun `order_item quantity below 1 rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            OrderItem(
                id = UUID.randomUUID(),
                orderId = UUID.randomUUID(),
                menuItemId = UUID.randomUUID(),
                name = "burger",
                quantity = 0,
                unitPriceMinor = 1000L,
                totalPriceMinor = 0L,
                createdBy = sys,
            )
        }
    }

    @Test
    fun `order_item unit_price_minor below 0 rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            OrderItem(
                id = UUID.randomUUID(),
                orderId = UUID.randomUUID(),
                menuItemId = UUID.randomUUID(),
                name = "burger",
                quantity = 1,
                unitPriceMinor = -1L,
                totalPriceMinor = 0L,
                createdBy = sys,
            )
        }
    }

    // ---------- IdempotencyRecord ----------

    @Test
    fun `idempotency_record valid scopes accepted`() {
        for (scope in IdempotencyRecord.VALID_SCOPES) {
            IdempotencyRecord(
                id = UUID.randomUUID(),
                scope = scope,
                idemKey = "idem_valid_length",
                requestHash = "a".repeat(64),
                createdBy = sys,
            )
        }
    }

    @Test
    fun `idempotency_record invalid scope rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            IdempotencyRecord(
                id = UUID.randomUUID(),
                scope = "trip_xxx",
                idemKey = "idem_valid_length",
                requestHash = "a".repeat(64),
                createdBy = sys,
            )
        }
    }

    @Test
    fun `idempotency_record request_hash length enforced`() {
        assertThrows(IllegalArgumentException::class.java) {
            IdempotencyRecord(
                id = UUID.randomUUID(),
                scope = IdempotencyRecord.SCOPE_ORDER_REQUEST,
                idemKey = "idem_valid_length",
                requestHash = "too_short",
                createdBy = sys,
            )
        }
    }

    @Test
    fun `idempotency_record double recordResponse rejected`() {
        val key = IdempotencyRecord(
            id = UUID.randomUUID(),
            scope = IdempotencyRecord.SCOPE_ORDER_REQUEST,
            idemKey = "idem_valid_length",
            requestHash = "a".repeat(64),
            createdBy = sys,
        )
        key.recordResponse(201, mapOf("id" to "x"), now)
        assertTrue(key.isCompleted())
        assertThrows(IllegalStateException::class.java) {
            key.recordResponse(200, mapOf("id" to "y"), now.plusSeconds(60))
        }
    }

    // ---------- OutboxEvent ----------

    @Test
    fun `outbox mark_published sets timestamp`() {
        val e = OutboxEvent(
            id = UUID.randomUUID(),
            aggregateType = "Order",
            aggregateId = UUID.randomUUID(),
            eventType = "food.order.placed.v1",
            topic = "food.order.placed.v1",
            payload = mapOf("x" to 1),
            correlationId = UUID.randomUUID(),
            createdBy = sys,
        )
        e.markPublished(now)
        assertEquals(now, e.publishedAt)
    }

    @Test
    fun `outbox mark_failed increments attempts`() {
        val e = OutboxEvent(
            id = UUID.randomUUID(),
            aggregateType = "Order",
            aggregateId = UUID.randomUUID(),
            eventType = "food.order.placed.v1",
            topic = "food.order.placed.v1",
            payload = mapOf("x" to 1),
            correlationId = UUID.randomUUID(),
            createdBy = sys,
        )
        e.markFailed("kafka_unreachable", now.plusSeconds(60))
        assertEquals(1, e.attempts)
        assertEquals("kafka_unreachable", e.lastError)
    }

    // ---------- OrderStateHistory invariants ----------

    @Test
    fun `order_state_history actor_kind validated at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            OrderStateHistory(
                id = UUID.randomUUID(),
                subjectId = UUID.randomUUID(),
                actorKind = "robot",
                toState = "delivered",
                correlationId = UUID.randomUUID(),
            )
        }
    }

    @Test
    fun `order_state_history subject_kind validated at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            OrderStateHistory(
                id = UUID.randomUUID(),
                subjectId = UUID.randomUUID(),
                subjectKind = "trip",
                actorKind = "system",
                toState = "delivered",
                correlationId = UUID.randomUUID(),
            )
        }
    }
}