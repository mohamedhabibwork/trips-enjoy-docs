package com.trips_enjoy.courier.conductor

import com.trips_enjoy.courier.application.CourierWriteService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The Conductor workflow workers for courier-service. Per
 * [ADR-0018](docs/architecture/adrs/0018-workflow-engine-conductor.md)
 * courier-service owns 3 of the 17 workflow IDs:
 *   * wf.courier.onboarding.v1   (this file)
 *   * wf.courier.kyc_verify.v1   (this file)
 *   * wf.courier.dispatch.v1     (this file)
 *
 * Each worker is a thin wrapper that translates a Conductor task input
 * map to a call into the CourierWriteService application layer. The
 * Conductor SDK is wired via the platform-spring-boot-starter.
 */
@Component
class CourierConductorWorkers(
    private val courierWriteService: CourierWriteService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun onboarding(input: Map<String, Any?>): Map<String, Any?> {
        val identityId = UUID.fromString(input["identity_id"] as String)
        val correlationId = UUID.fromString(input["correlation_id"] as String)
        val actingUser = UUID.fromString(input["acting_user_id"] as String)
        val idempotencyKey = input["idempotency_key"] as String
        val requestHash = (input["request_hash"] as? String) ?: sha256(idempotencyKey)

        val courier = courierWriteService.create(
            identityId = identityId,
            name = input["name"] as? String,
            email = input["email"] as? String,
            phone = input["phone"] as? String,
            correlationId = correlationId,
            createdBy = actingUser,
            idempotencyKey = idempotencyKey,
            requestHash = requestHash,
        )

        @Suppress("UNCHECKED_CAST")
        val cityIds = (input["city_ids"] as? List<String>) ?: emptyList()
        val grantedCityIds = cityIds.mapNotNull { cityIdStr ->
            try {
                courierWriteService.grantCityEligibility(
                    courierId = courier.id,
                    cityId = UUID.fromString(cityIdStr),
                    notes = "granted at onboarding",
                    correlationId = correlationId,
                    actingUser = actingUser,
                )
                cityIdStr
            } catch (e: Exception) {
                log.warn("city eligibility grant failed for courier {} city {}: {}", courier.id, cityIdStr, e.message)
                null
            }
        }

        return mapOf(
            "courier_id" to courier.id.toString(),
            "identity_id" to identityId.toString(),
            "status" to courier.status,
            "granted_city_ids" to grantedCityIds,
        )
    }

    fun kycVerify(input: Map<String, Any?>): Map<String, Any?> {
        val courierId = UUID.fromString(input["courier_id"] as String)
        val correlationId = UUID.fromString(input["correlation_id"] as String)
        val actingUser = UUID.fromString(input["acting_user_id"] as String)
        val courier = courierWriteService.approve(
            courierId = courierId,
            correlationId = correlationId,
            actingUser = actingUser,
        )
        return mapOf(
            "courier_id" to courier.id.toString(),
            "status" to courier.status,
        )
    }

    fun dispatch(input: Map<String, Any?>): Map<String, Any?> {
        val courierId = UUID.fromString(input["courier_id"] as String)
        val correlationId = UUID.fromString(input["correlation_id"] as String)
        val actingUser = UUID.fromString(input["acting_user_id"] as String)
        val courier = courierWriteService.touchOnline(
            courierId = courierId,
            correlationId = correlationId,
            actingUser = actingUser,
        )
        return mapOf(
            "courier_id" to courier.id.toString(),
            "last_online_at" to courier.lastOnlineAt?.toString(),
        )
    }

    private fun sha256(payload: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(payload.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}