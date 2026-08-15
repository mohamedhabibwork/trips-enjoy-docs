package com.trips_enjoy.restaurant.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for the Restaurant 8-state state machine. Covers:
 *   * submit / approve / reject from draft or pending_review
 *   * goOnline / goOffline from approved or online
 *   * suspend / reinstate / close lifecycle
 *   * resubmit from pending_review or rejected
 *   * cascadeSuspend / cascadeReinstate / cascadeClose (merchant-level events)
 *   * applyRating recomputes the aggregate rating
 *   * Illegal transitions raise IllegalStateException
 */
class RestaurantStateMachineTest {

    private val now = Instant.parse("2026-08-15T12:00:00Z")
    private val owner = UUID.randomUUID()
    private val admin = UUID.randomUUID()
    private val merchant = UUID.randomUUID()

    private fun newRestaurant(state: String = Restaurant.STATE_DRAFT): Restaurant = Restaurant(
        id = UUID.randomUUID(),
        merchantId = merchant,
        name = "Test Bistro",
        slug = "test-bistro",
        type = Restaurant.TYPE_RESTAURANT,
        state = state,
        createdBy = owner,
        updatedBy = owner,
    )

    @Test
    fun `submit moves draft to pending_review`() {
        val restaurant = newRestaurant()
        restaurant.submit(owner, now)
        assertEquals(Restaurant.STATE_PENDING_REVIEW, restaurant.state)
    }

    @Test
    fun `submit rejects from approved state`() {
        val restaurant = newRestaurant(Restaurant.STATE_APPROVED)
        assertThrows(IllegalStateException::class.java) {
            restaurant.submit(owner, now)
        }
    }

    @Test
    fun `approve moves pending_review to approved`() {
        val restaurant = newRestaurant(Restaurant.STATE_PENDING_REVIEW)
        restaurant.approve(admin, now)
        assertEquals(Restaurant.STATE_APPROVED, restaurant.state)
    }

    @Test
    fun `approve rejects from draft state`() {
        val restaurant = newRestaurant(Restaurant.STATE_DRAFT)
        assertThrows(IllegalStateException::class.java) {
            restaurant.approve(admin, now)
        }
    }

    @Test
    fun `reject requires non-blank reason`() {
        val restaurant = newRestaurant(Restaurant.STATE_PENDING_REVIEW)
        assertThrows(IllegalArgumentException::class.java) {
            restaurant.reject("", admin, now)
        }
    }

    @Test
    fun `reject moves pending_review to rejected with reason`() {
        val restaurant = newRestaurant(Restaurant.STATE_PENDING_REVIEW)
        restaurant.reject("kyc_incomplete", admin, now)
        assertEquals(Restaurant.STATE_REJECTED, restaurant.state)
    }

    @Test
    fun `goOnline moves approved to online and sets online true`() {
        val restaurant = newRestaurant(Restaurant.STATE_APPROVED)
        restaurant.goOnline(owner, now)
        assertEquals(Restaurant.STATE_ONLINE, restaurant.state)
        assertEquals(true, restaurant.online)
    }

    @Test
    fun `goOnline moves offline back to online`() {
        val restaurant = newRestaurant(Restaurant.STATE_OFFLINE)
        restaurant.goOnline(owner, now)
        assertEquals(Restaurant.STATE_ONLINE, restaurant.state)
    }

    @Test
    fun `goOffline moves online to offline and sets online false`() {
        val restaurant = newRestaurant(Restaurant.STATE_ONLINE)
        restaurant.goOffline(owner, now)
        assertEquals(Restaurant.STATE_OFFLINE, restaurant.state)
        assertEquals(false, restaurant.online)
    }

    @Test
    fun `suspend requires non-blank reason`() {
        val restaurant = newRestaurant(Restaurant.STATE_APPROVED)
        assertThrows(IllegalArgumentException::class.java) {
            restaurant.suspend("", admin, now)
        }
    }

    @Test
    fun `suspend moves approved to suspended`() {
        val restaurant = newRestaurant(Restaurant.STATE_APPROVED)
        restaurant.suspend("policy_violation", admin, now)
        assertEquals(Restaurant.STATE_SUSPENDED, restaurant.state)
    }

    @Test
    fun `suspend moves online to suspended and sets online false`() {
        val restaurant = newRestaurant(Restaurant.STATE_ONLINE)
        restaurant.suspend("quality_complaint", admin, now)
        assertEquals(Restaurant.STATE_SUSPENDED, restaurant.state)
        assertEquals(false, restaurant.online)
    }

    @Test
    fun `reinstate moves suspended back to approved`() {
        val restaurant = newRestaurant(Restaurant.STATE_SUSPENDED)
        restaurant.reinstate(admin, now)
        assertEquals(Restaurant.STATE_APPROVED, restaurant.state)
    }

    @Test
    fun `reinstate rejects from non-suspended state`() {
        val restaurant = newRestaurant(Restaurant.STATE_APPROVED)
        assertThrows(IllegalStateException::class.java) {
            restaurant.reinstate(admin, now)
        }
    }

    @Test
    fun `close moves approved to closed and sets online false`() {
        val restaurant = newRestaurant(Restaurant.STATE_APPROVED)
        restaurant.close(owner, now)
        assertEquals(Restaurant.STATE_CLOSED, restaurant.state)
        assertEquals(false, restaurant.online)
    }

    @Test
    fun `close is idempotent rejected on already closed`() {
        val restaurant = newRestaurant(Restaurant.STATE_CLOSED)
        assertThrows(IllegalStateException::class.java) {
            restaurant.close(owner, now)
        }
    }

    @Test
    fun `close rejects from draft state`() {
        val restaurant = newRestaurant(Restaurant.STATE_DRAFT)
        assertThrows(IllegalStateException::class.java) {
            restaurant.close(owner, now)
        }
    }

    @Test
    fun `resubmit moves pending_review back to pending_review`() {
        val restaurant = newRestaurant(Restaurant.STATE_PENDING_REVIEW)
        restaurant.resubmit(owner, now)
        assertEquals(Restaurant.STATE_PENDING_REVIEW, restaurant.state)
    }

    @Test
    fun `resubmit moves rejected to pending_review`() {
        val restaurant = newRestaurant(Restaurant.STATE_REJECTED)
        restaurant.resubmit(owner, now)
        assertEquals(Restaurant.STATE_PENDING_REVIEW, restaurant.state)
    }

    @Test
    fun `cascadeSuspend moves approved to suspended with cascade audit`() {
        val restaurant = newRestaurant(Restaurant.STATE_APPROVED)
        restaurant.cascadeSuspend("merchant_suspended", UUID.randomUUID(), now)
        assertEquals(Restaurant.STATE_SUSPENDED, restaurant.state)
        assertEquals(false, restaurant.online)
    }

    @Test
    fun `cascadeReinstate moves suspended back to approved`() {
        val restaurant = newRestaurant(Restaurant.STATE_SUSPENDED)
        restaurant.cascadeReinstate(UUID.randomUUID(), now)
        assertEquals(Restaurant.STATE_APPROVED, restaurant.state)
    }

    @Test
    fun `cascadeClose is idempotent rejected on already closed`() {
        val restaurant = newRestaurant(Restaurant.STATE_CLOSED)
        assertThrows(IllegalStateException::class.java) {
            restaurant.cascadeClose(UUID.randomUUID(), now)
        }
    }

    @Test
    fun `applyRating recomputes aggregate rating`() {
        val restaurant = newRestaurant(Restaurant.STATE_APPROVED)
        restaurant.applyRating(BigDecimal(5), now)
        assertEquals(1, restaurant.reviewCount)
        assertEquals(BigDecimal("5.00"), restaurant.avgRating)
        restaurant.applyRating(BigDecimal(3), now.plusSeconds(60))
        assertEquals(2, restaurant.reviewCount)
        assertEquals(BigDecimal("4.00"), restaurant.avgRating)
    }

    @Test
    fun `applyRating rejects out-of-range`() {
        val restaurant = newRestaurant(Restaurant.STATE_APPROVED)
        assertThrows(IllegalArgumentException::class.java) {
            restaurant.applyRating(BigDecimal(0), now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            restaurant.applyRating(BigDecimal(6), now)
        }
    }

    @Test
    fun `slug validation rejects invalid format at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            Restaurant(
                id = UUID.randomUUID(),
                merchantId = merchant,
                name = "X",
                slug = "INVALID SLUG WITH SPACES",
                type = Restaurant.TYPE_RESTAURANT,
                createdBy = owner,
                updatedBy = owner,
            )
        }
    }

    @Test
    fun `slug validation rejects uppercase at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            Restaurant(
                id = UUID.randomUUID(),
                merchantId = merchant,
                name = "X",
                slug = "Has-Uppercase",
                type = Restaurant.TYPE_RESTAURANT,
                createdBy = owner,
                updatedBy = owner,
            )
        }
    }

    @Test
    fun `name validation rejects empty at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            Restaurant(
                id = UUID.randomUUID(),
                merchantId = merchant,
                name = "",
                slug = "ok-slug",
                type = Restaurant.TYPE_RESTAURANT,
                createdBy = owner,
                updatedBy = owner,
            )
        }
    }

    @Test
    fun `name validation rejects over 120 chars at construction`() {
        val longName = "x".repeat(121)
        assertThrows(IllegalArgumentException::class.java) {
            Restaurant(
                id = UUID.randomUUID(),
                merchantId = merchant,
                name = longName,
                slug = "ok-slug",
                type = Restaurant.TYPE_RESTAURANT,
                createdBy = owner,
                updatedBy = owner,
            )
        }
    }

    @Test
    fun `type validation rejects unknown at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            Restaurant(
                id = UUID.randomUUID(),
                merchantId = merchant,
                name = "X",
                slug = "ok-slug",
                type = "food_truck_pizza",
                createdBy = owner,
                updatedBy = owner,
            )
        }
    }
}