package com.trips_enjoy.driver.conductor

import com.trips_enjoy.driver.application.DriverWriteService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The Conductor workflow workers for driver-service. Per
 * [ADR-0018](docs/architecture/adrs/0018-workflow-engine-conductor.md)
 * and [shared/CONDUCTOR_WORKFLOWS.md](docs/shared/CONDUCTOR_WORKFLOWS.md)
 * driver-service owns 3 of the 17 workflow IDs:
 *   * wf.driver.onboarding.v1   (this file)
 *   * wf.driver.kyc_verify.v1   (this file)
 *   * wf.driver.dispatch.v1     (this file)
 *
 * Each worker is a thin wrapper that translates a Conductor task input
 * map to a call into the DriverWriteService application layer. The
 * Conductor SDK is wired via the platform-spring-boot-starter.
 */
@Component
class DriverConductorWorkers(
    private val driverWriteService: DriverWriteService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Conductor task: driver.onboarding — drives the KYC + background
     * check + city eligibility grant sequence for a new driver.
     *
     * Input: { identity_id, name, email, phone, city_ids, kyc_verification_id, background_check_verification_id }
     * Output: { driver_id, status, granted_city_ids }
     */
    fun onboarding(input: Map<String, Any?>): Map<String, Any?> {
        val identityId = UUID.fromString(input["identity_id"] as String)
        val correlationId = UUID.fromString(input["correlation_id"] as String)
        val actingUser = UUID.fromString(input["acting_user_id"] as String)
        val idempotencyKey = input["idempotency_key"] as String
        val requestHash = (input["request_hash"] as? String) ?: sha256(idempotencyKey)

        val driver = driverWriteService.create(
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
                driverWriteService.grantCityEligibility(
                    driverId = driver.id,
                    cityId = UUID.fromString(cityIdStr),
                    notes = "granted at onboarding",
                    correlationId = correlationId,
                    actingUser = actingUser,
                )
                cityIdStr
            } catch (e: Exception) {
                log.warn("city eligibility grant failed for driver {} city {}: {}", driver.id, cityIdStr, e.message)
                null
            }
        }

        return mapOf(
            "driver_id" to driver.id.toString(),
            "identity_id" to identityId.toString(),
            "status" to driver.status,
            "granted_city_ids" to grantedCityIds,
        )
    }

    /**
     * Conductor task: driver.kyc_verify — triggered after the KYC
     * provider callback returns verified. Approves the driver and
     * sets the verified_at timestamp.
     *
     * Input: { driver_id, kyc_verification_id, background_check_verification_id }
     * Output: { driver_id, status, kyc_verified_at }
     */
    fun kycVerify(input: Map<String, Any?>): Map<String, Any?> {
        val driverId = UUID.fromString(input["driver_id"] as String)
        val correlationId = UUID.fromString(input["correlation_id"] as String)
        val actingUser = UUID.fromString(input["acting_user_id"] as String)

        val driver = driverWriteService.approve(
            driverId = driverId,
            correlationId = correlationId,
            actingUser = actingUser,
        )
        return mapOf(
            "driver_id" to driver.id.toString(),
            "status" to driver.status,
        )
    }

    /**
     * Conductor task: driver.dispatch — marks a driver as online (the
     * dispatch saga calls this when the driver accepts a ride offer).
     *
     * Input: { driver_id }
     * Output: { driver_id, last_online_at }
     */
    fun dispatch(input: Map<String, Any?>): Map<String, Any?> {
        val driverId = UUID.fromString(input["driver_id"] as String)
        val correlationId = UUID.fromString(input["correlation_id"] as String)
        val actingUser = UUID.fromString(input["acting_user_id"] as String)
        val driver = driverWriteService.touchOnline(
            driverId = driverId,
            correlationId = correlationId,
            actingUser = actingUser,
        )
        return mapOf(
            "driver_id" to driver.id.toString(),
            "last_online_at" to driver.lastOnlineAt?.toString(),
        )
    }

    private fun sha256(payload: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(payload.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}