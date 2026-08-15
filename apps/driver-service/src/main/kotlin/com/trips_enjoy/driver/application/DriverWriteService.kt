package com.trips_enjoy.driver.application

import com.trips_enjoy.driver.domain.Driver
import com.trips_enjoy.driver.domain.DriverAuditLog
import com.trips_enjoy.driver.domain.DriverAuditLogRepository
import com.trips_enjoy.driver.domain.DriverCityEligibility
import com.trips_enjoy.driver.domain.DriverCityEligibilityRepository
import com.trips_enjoy.driver.domain.DriverDocument
import com.trips_enjoy.driver.domain.DriverDocumentRepository
import com.trips_enjoy.driver.domain.DriverRatingHistory
import com.trips_enjoy.driver.domain.DriverRatingHistoryRepository
import com.trips_enjoy.driver.domain.DriverRepository
import com.trips_enjoy.driver.domain.IdempotencyKey
import com.trips_enjoy.driver.domain.OutboxEvent
import com.trips_enjoy.driver.domain.OutboxEventRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * The driver write-service — encapsulates every state-machine mutation
 * (create / approve / reject / suspend / reinstate / disable / erase),
 * document lifecycle (add / verify / reject / expire), city eligibility
 * grant/revoke, rating line items, and primary-vehicle change. Every
 * mutation is idempotent on the Idempotency-Key, emits a row to
 * `driver_audit_log`, and writes one or more rows to `outbox_events`
 * for kafka publication by `OutboxPublisher`.
 *
 * Mirrors the customer-service `CustomerWriteService` + payment-service
 * `PaymentIntentService` pattern.
 */
@Service
class DriverWriteService(
    private val driverRepository: DriverRepository,
    private val documentRepository: DriverDocumentRepository,
    private val cityEligibilityRepository: DriverCityEligibilityRepository,
    private val ratingHistoryRepository: DriverRatingHistoryRepository,
    private val auditLogRepository: DriverAuditLogRepository,
    private val outboxRepository: OutboxEventRepository,
    private val idemService: IdempotencyService,
) {

    @Transactional
    fun create(
        identityId: UUID,
        name: String?,
        email: String?,
        phone: String?,
        correlationId: UUID,
        createdBy: UUID,
        idempotencyKey: String,
        requestHash: String,
    ): Driver {
        val existing = idemService.findExisting(IdempotencyKey.SCOPE_DRIVER_CREATE, idempotencyKey)
        if (existing != null) {
            require(existing.requestHash == requestHash) { "idempotency key body mismatch" }
            return driverRepository.findByIdentityIdAndDeletedAtIsNull(identityId)
                ?: error("idempotency recorded but no driver for identity $identityId")
        }
        val now = Instant.now()
        val driver = Driver(
            id = UUID.randomUUID(),
            identityId = identityId,
            name = name,
            email = email,
            phone = phone,
            createdAt = now,
            updatedAt = now,
            createdBy = createdBy,
            updatedBy = createdBy,
        )
        driverRepository.save(driver)
        writeAudit(
            driverId = driver.id,
            action = DriverAuditLog.ACTION_CREATED,
            before = null,
            after = mapOf("status" to driver.status, "identity_id" to identityId.toString()),
            actorId = createdBy,
            reason = null,
            correlationId = correlationId,
        )
        idemService.record(
            IdempotencyKey.SCOPE_DRIVER_CREATE,
            idempotencyKey,
            requestHash,
            201,
            mapOf("driver_id" to driver.id.toString()),
            createdBy,
            now,
        )
        emitCreated(driver, correlationId, createdBy)
        return driver
    }

    @Transactional
    fun approve(driverId: UUID, correlationId: UUID, actingUser: UUID): Driver {
        val now = Instant.now()
        val driver = requireActive(driverId)
        val before = mapOf("status" to driver.status)
        driver.approve(now)
        writeAudit(driverId, DriverAuditLog.ACTION_APPROVED, before, mapOf("status" to driver.status), actingUser, null, correlationId)
        emitStateChange(driver, "driver.approved.v1", correlationId, actingUser)
        return driver
    }

    @Transactional
    fun reject(driverId: UUID, reason: String, correlationId: UUID, actingUser: UUID): Driver {
        val now = Instant.now()
        val driver = requireActive(driverId)
        val before = mapOf("status" to driver.status)
        driver.reject(reason, now)
        writeAudit(driverId, DriverAuditLog.ACTION_REJECTED, before, mapOf("status" to driver.status, "rejected_reason" to reason), actingUser, reason, correlationId)
        emitStateChange(driver, "driver.rejected.v1", correlationId, actingUser)
        return driver
    }

    @Transactional
    fun suspend(driverId: UUID, reason: String, correlationId: UUID, actingUser: UUID): Driver {
        val now = Instant.now()
        val driver = requireActive(driverId)
        val before = mapOf("status" to driver.status)
        driver.suspend(reason, actingUser, now)
        writeAudit(driverId, DriverAuditLog.ACTION_SUSPENDED, before, mapOf("status" to driver.status, "suspended_reason" to reason), actingUser, reason, correlationId)
        emitStateChange(driver, "driver.suspended.v1", correlationId, actingUser)
        return driver
    }

    @Transactional
    fun reinstate(driverId: UUID, correlationId: UUID, actingUser: UUID): Driver {
        val now = Instant.now()
        val driver = driverRepository.findByIdAndDeletedAtIsNull(driverId)
            ?: error("driver $driverId not found")
        val before = mapOf("status" to driver.status)
        driver.reinstate(now)
        writeAudit(driverId, DriverAuditLog.ACTION_REINSTATED, before, mapOf("status" to driver.status), actingUser, null, correlationId)
        emitStateChange(driver, "driver.reinstated.v1", correlationId, actingUser)
        return driver
    }

    @Transactional
    fun disable(driverId: UUID, correlationId: UUID, actingUser: UUID): Driver {
        val now = Instant.now()
        val driver = requireActive(driverId)
        val before = mapOf("status" to driver.status)
        driver.disable(now)
        writeAudit(driverId, DriverAuditLog.ACTION_DISABLED, before, mapOf("status" to driver.status), actingUser, null, correlationId)
        emitStateChange(driver, "driver.disabled.v1", correlationId, actingUser)
        return driver
    }

    @Transactional
    fun erase(driverId: UUID, correlationId: UUID, actingUser: UUID): Driver {
        val now = Instant.now()
        val driver = driverRepository.findByIdAndDeletedAtIsNull(driverId)
            ?: error("driver $driverId not found")
        val before = mapOf("status" to driver.status)
        driver.erase(now)
        writeAudit(driverId, DriverAuditLog.ACTION_ERASED, before, mapOf("status" to driver.status), actingUser, null, correlationId)
        emitStateChange(driver, "driver.erased.v1", correlationId, actingUser)
        return driver
    }

    @Transactional
    fun addDocument(
        driverId: UUID,
        type: String,
        fileId: UUID,
        critical: Boolean,
        expiryDate: Instant?,
        correlationId: UUID,
        actingUser: UUID,
    ): DriverDocument {
        val now = Instant.now()
        val driver = requireActive(driverId)
        val doc = DriverDocument(
            id = UUID.randomUUID(),
            driverId = driverId,
            type = type,
            fileId = fileId,
            critical = critical,
            expiryDate = expiryDate,
            createdBy = actingUser,
            updatedBy = actingUser,
        )
        documentRepository.save(doc)
        writeAudit(driverId, DriverAuditLog.ACTION_DOCUMENT_ADDED, null, mapOf("document_id" to doc.id.toString(), "type" to type), actingUser, null, correlationId)
        emitEvent(driverId, "driver.document.added.v1", correlationId, actingUser, mapOf("document_id" to doc.id.toString(), "type" to type, "critical" to critical))
        return doc
    }

    @Transactional
    fun verifyDocument(
        documentId: UUID,
        verificationId: UUID,
        correlationId: UUID,
        actingUser: UUID,
    ): DriverDocument {
        val now = Instant.now()
        val doc = documentRepository.findById(documentId).orElseThrow()
        doc.verify(verificationId, now)
        writeAudit(doc.driverId, DriverAuditLog.ACTION_DOCUMENT_VERIFIED, null, mapOf("document_id" to doc.id.toString()), actingUser, null, correlationId)
        emitEvent(doc.driverId, "driver.document.verified.v1", correlationId, actingUser, mapOf("document_id" to doc.id.toString()))
        return doc
    }

    @Transactional
    fun grantCityEligibility(
        driverId: UUID,
        cityId: UUID,
        notes: String?,
        correlationId: UUID,
        actingUser: UUID,
    ): DriverCityEligibility {
        val now = Instant.now()
        val driver = requireActive(driverId)
        require(cityEligibilityRepository.findActive(driverId, cityId) == null) {
            "eligibility for driver $driverId + city $cityId already active"
        }
        val eligibility = DriverCityEligibility(
            id = UUID.randomUUID(),
            driverId = driverId,
            cityId = cityId,
            grantedBy = actingUser,
            notes = notes,
            createdBy = actingUser,
            updatedBy = actingUser,
        )
        cityEligibilityRepository.save(eligibility)
        writeAudit(driverId, DriverAuditLog.ACTION_CITY_GRANTED, null, mapOf("city_id" to cityId.toString()), actingUser, notes, correlationId)
        emitEvent(driverId, "driver.eligibility.granted.v1", correlationId, actingUser, mapOf("city_id" to cityId.toString()))
        return eligibility
    }

    @Transactional
    fun revokeCityEligibility(
        driverId: UUID,
        cityId: UUID,
        correlationId: UUID,
        actingUser: UUID,
    ): DriverCityEligibility {
        val now = Instant.now()
        val eligibility = cityEligibilityRepository.findActive(driverId, cityId)
            ?: error("no active eligibility for driver $driverId + city $cityId")
        eligibility.revoke(actingUser, now)
        writeAudit(driverId, DriverAuditLog.ACTION_CITY_REVOKED, null, mapOf("city_id" to cityId.toString()), actingUser, null, correlationId)
        emitEvent(driverId, "driver.eligibility.revoked.v1", correlationId, actingUser, mapOf("city_id" to cityId.toString()))
        return eligibility
    }

    @Transactional
    fun applyRating(
        driverId: UUID,
        requestId: UUID,
        service: String,
        rating: Short,
        comment: String?,
        correlationId: UUID,
        actingUser: UUID,
    ): Driver {
        require(rating.toInt() in 1..5) { "rating must be 1..5" }
        val now = Instant.now()
        val driver = requireActive(driverId)
        val line = DriverRatingHistory(
            id = UUID.randomUUID(),
            driverId = driverId,
            requestId = requestId,
            service = service,
            rating = rating,
            comment = comment,
            correlationId = correlationId,
            createdBy = actingUser,
        )
        ratingHistoryRepository.save(line)
        driver.applyRating(BigDecimal(rating.toInt()), now)
        writeAudit(driverId, DriverAuditLog.ACTION_RATING_ADDED, null, mapOf("rating" to rating.toInt(), "request_id" to requestId.toString()), actingUser, null, correlationId)
        emitEvent(driverId, "driver.rating.added.v1", correlationId, actingUser, mapOf("rating" to rating.toInt(), "new_rating" to driver.rating.toDouble(), "new_count" to driver.ratingCount))
        return driver
    }

    @Transactional
    fun setPrimaryVehicle(
        driverId: UUID,
        vehicleId: UUID,
        correlationId: UUID,
        actingUser: UUID,
    ): Driver {
        val now = Instant.now()
        val driver = requireActive(driverId)
        val before = mapOf("primary_vehicle_id" to driver.primaryVehicleId?.toString())
        driver.setPrimaryVehicle(vehicleId, now)
        writeAudit(driverId, DriverAuditLog.ACTION_PRIMARY_VEHICLE_CHANGED, before, mapOf("primary_vehicle_id" to vehicleId.toString()), actingUser, null, correlationId)
        emitEvent(driverId, "driver.primary_vehicle.changed.v1", correlationId, actingUser, mapOf("vehicle_id" to vehicleId.toString()))
        return driver
    }

    @Transactional
    fun updateProfile(
        driverId: UUID,
        name: String?,
        email: String?,
        phone: String?,
        correlationId: UUID,
        actingUser: UUID,
    ): Driver {
        val now = Instant.now()
        val driver = requireActive(driverId)
        val before = mapOf("name" to driver.name, "email" to driver.email, "phone" to driver.phone)
        driver.name = name
        driver.email = email
        driver.phone = phone
        driver.updatedAt = now
        driver.rowVersion += 1
        writeAudit(driverId, DriverAuditLog.ACTION_PROFILE_UPDATED, before, mapOf("name" to name, "email" to email, "phone" to phone), actingUser, null, correlationId)
        emitEvent(driverId, "driver.profile.updated.v1", correlationId, actingUser, mapOf("name" to name, "email" to email))
        return driver
    }

    @Transactional
    fun touchOnline(driverId: UUID, correlationId: UUID, actingUser: UUID): Driver {
        val now = Instant.now()
        val driver = requireActive(driverId)
        driver.touchOnline(now)
        emitEvent(driverId, "driver.online.v1", correlationId, actingUser, mapOf("last_online_at" to now.toString()))
        return driver
    }

    private fun requireActive(driverId: UUID): Driver {
        val driver = driverRepository.findByIdAndDeletedAtIsNull(driverId)
            ?: error("driver $driverId not found")
        check(driver.status != Driver.STATUS_ERASED) { "driver $driverId is erased" }
        return driver
    }

    private fun writeAudit(
        driverId: UUID,
        action: String,
        before: Map<String, Any?>?,
        after: Map<String, Any?>?,
        actorId: UUID,
        reason: String?,
        correlationId: UUID,
    ) {
        auditLogRepository.save(
            DriverAuditLog(
                id = UUID.randomUUID(),
                driverId = driverId,
                action = action,
                before = before,
                after = after,
                actorId = actorId,
                reason = reason,
                correlationId = correlationId,
            ),
        )
    }

    private fun emitCreated(driver: Driver, correlationId: UUID, createdBy: UUID) {
        emitEvent(
            driver.id,
            "driver.created.v1",
            correlationId,
            createdBy,
            mapOf(
                "driver_id" to driver.id.toString(),
                "identity_id" to driver.identityId.toString(),
                "status" to driver.status,
                "rating" to driver.rating.toDouble(),
            ),
        )
    }

    private fun emitStateChange(driver: Driver, eventType: String, correlationId: UUID, createdBy: UUID) {
        emitEvent(
            driver.id,
            eventType,
            correlationId,
            createdBy,
            mapOf(
                "driver_id" to driver.id.toString(),
                "identity_id" to driver.identityId.toString(),
                "status" to driver.status,
            ),
        )
    }

    private fun emitEvent(
        driverId: UUID,
        eventType: String,
        correlationId: UUID,
        createdBy: UUID,
        payload: Map<String, Any?>,
    ) {
        outboxRepository.save(
            OutboxEvent(
                id = UUID.randomUUID(),
                aggregateType = "Driver",
                aggregateId = driverId,
                eventType = eventType,
                topic = eventType,
                payload = payload,
                correlationId = correlationId,
                createdBy = createdBy,
            ),
        )
    }
}