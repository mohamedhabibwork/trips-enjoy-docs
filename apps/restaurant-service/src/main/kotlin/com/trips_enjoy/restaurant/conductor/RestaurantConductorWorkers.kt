package com.trips_enjoy.restaurant.conductor

import com.trips_enjoy.restaurant.application.RestaurantWriteService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The Conductor workflow workers for restaurant-service. Per
 * [ADR-0018](docs/architecture/adrs/0018-workflow-engine-conductor.md)
 * restaurant-service owns 3 of the 17 workflow IDs:
 *   * wf.restaurant.onboarding.v1   (this file)
 *   * wf.restaurant.kyc_verify.v1   (this file)
 *   * wf.restaurant.toggle.v1       (this file)
 *
 * Each worker is a thin wrapper that translates a Conductor task input
 * map to a call into the RestaurantWriteService application layer.
 */
@Component
class RestaurantConductorWorkers(
    private val restaurantWriteService: RestaurantWriteService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Conductor task: restaurant.onboarding — drives the create → submit →
     * approve sequence for a new restaurant.
     *
     * Input: { merchant_id, name, slug, type, description }
     * Output: { restaurant_id, state }
     */
    fun onboarding(input: Map<String, Any?>): Map<String, Any?> {
        val merchantId = UUID.fromString(input["merchant_id"] as String)
        val correlationId = UUID.fromString(input["correlation_id"] as String)
        val actingUser = UUID.fromString(input["acting_user_id"] as String)
        val idempotencyKey = input["idempotency_key"] as String
        val requestHash = (input["request_hash"] as? String) ?: sha256(idempotencyKey)

        val restaurant = restaurantWriteService.create(
            merchantId = merchantId,
            name = input["name"] as String,
            slug = input["slug"] as String,
            type = input["type"] as String,
            description = input["description"] as? String,
            correlationId = correlationId,
            createdBy = actingUser,
            idempotencyKey = idempotencyKey,
            requestHash = requestHash,
        )
        val restaurantId = requireNotNull(restaurant.id) { "Restaurant.id must be assigned after persist" }
        restaurantWriteService.submit(
            restaurantId = restaurantId,
            correlationId = correlationId,
            actingUser = actingUser,
        )
        return mapOf(
            "restaurant_id" to restaurantId.toString(),
            "merchant_id" to merchantId.toString(),
            "state" to RestaurantState.PENDING_REVIEW.value,
        )
    }

    /**
     * Conductor task: restaurant.kyc_verify — KYC provider callback →
     * admin approve (or reject on failure).
     *
     * Input: { restaurant_id, approved, reason? }
     * Output: { restaurant_id, state }
     */
    fun kycVerify(input: Map<String, Any?>): Map<String, Any?> {
        val restaurantId = UUID.fromString(input["restaurant_id"] as String)
        val correlationId = UUID.fromString(input["correlation_id"] as String)
        val actingUser = UUID.fromString(input["acting_user_id"] as String)
        val approved = input["approved"] as? Boolean ?: true
        val restaurant = if (approved) {
            restaurantWriteService.approve(
                restaurantId = restaurantId,
                correlationId = correlationId,
                actingUser = actingUser,
            )
        } else {
            restaurantWriteService.reject(
                restaurantId = restaurantId,
                reason = input["reason"] as? String ?: "kyc_failed",
                correlationId = correlationId,
                actingUser = actingUser,
            )
        }
        return mapOf(
            "restaurant_id" to requireNotNull(restaurant.id).toString(),
            "state" to restaurant.state,
        )
    }

    /**
     * Conductor task: restaurant.toggle — flips online/offline state.
     *
     * Input: { restaurant_id, online }
     * Output: { restaurant_id, online }
     */
    fun toggle(input: Map<String, Any?>): Map<String, Any?> {
        val restaurantId = UUID.fromString(input["restaurant_id"] as String)
        val correlationId = UUID.fromString(input["correlation_id"] as String)
        val actingUser = UUID.fromString(input["acting_user_id"] as String)
        val online = input["online"] as Boolean
        val restaurant = if (online) {
            restaurantWriteService.goOnline(restaurantId, correlationId, actingUser)
        } else {
            restaurantWriteService.goOffline(restaurantId, correlationId, actingUser)
        }
        return mapOf(
            "restaurant_id" to requireNotNull(restaurant.id).toString(),
            "online" to restaurant.online,
        )
    }

    private fun sha256(payload: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(payload.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Type-safe wrapper for the 8 restaurant states (used by the
 * Conductor workers + tests).
 */
enum class RestaurantState(val value: String) {
    DRAFT("draft"),
    PENDING_REVIEW("pending_review"),
    APPROVED("approved"),
    REJECTED("rejected"),
    ONLINE("online"),
    OFFLINE("offline"),
    SUSPENDED("suspended"),
    CLOSED("closed");

    companion object {
        fun fromValue(v: String): RestaurantState = entries.first { it.value == v }
    }
}