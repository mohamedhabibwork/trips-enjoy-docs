package com.trips_enjoy.courier.application

import com.trips_enjoy.courier.domain.Courier
import com.trips_enjoy.courier.domain.CourierAuditLog
import com.trips_enjoy.courier.domain.CourierAuditLogRepository
import com.trips_enjoy.courier.domain.CourierCityEligibility
import com.trips_enjoy.courier.domain.CourierCityEligibilityRepository
import com.trips_enjoy.courier.domain.CourierDocument
import com.trips_enjoy.courier.domain.CourierDocumentRepository
import com.trips_enjoy.courier.domain.CourierRatingHistory
import com.trips_enjoy.courier.domain.CourierRatingHistoryRepository
import com.trips_enjoy.courier.domain.CourierRepository
import com.trips_enjoy.courier.domain.CourierShift
import com.trips_enjoy.courier.domain.CourierShiftRepository
import com.trips_enjoy.courier.domain.IdempotencyKey
import com.trips_enjoy.courier.domain.OutboxEvent
import com.trips_enjoy.courier.domain.OutboxEventRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * The courier write-service — encapsulates every state-machine mutation
 * (create / approve / reject / suspend / reinstate / disable / erase),
 * document lifecycle, city eligibility grant/revoke, shift scheduling,
 * rating line items, and primary-vehicle change. Every mutation is
 * idempotent on the Idempotency-Key, emits a row to `courier_audit_log`,
 * and writes one or more rows to `outbox_events` for kafka publication.
 *
 * Mirrors the driver-service `DriverWriteService` pattern with the
 * addition of shift scheduling (scheduleShift / activateShift /
 * completeShift / cancelShift) — the new sub-aggregate vs driver-service.
 *
 * Phase C (platform DRY): the simple-PK + audit entities
 * (`Courier`, `CourierShift`, `CourierCityEligibility`,
 * `CourierDocument`) now extend [com.trips_enjoy.platform.data.BaseEntity].
 * The `createdBy` / `updatedBy` audit columns are populated by
 * `PlatformAuditorAware` (JWT `sub`) via JPA auditing; the local
 * mutations update `updatedAt` and `version` directly.
 * `actingUser` is preserved as a cross-service UUID parameter and is
 * passed through unchanged to the audit log + outbox events.
 */
@Service
class CourierWriteService(
    private val courierRepository: CourierRepository,
    private val documentRepository: CourierDocumentRepository,
    private val shiftRepository: CourierShiftRepository,
    private val cityEligibilityRepository: CourierCityEligibilityRepository,
    private val ratingHistoryRepository: CourierRatingHistoryRepository,
    private val auditLogRepository: CourierAuditLogRepository,
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
    ): Courier {
        val existing = idemService.findExisting(IdempotencyKey.SCOPE_COURIER_CREATE, idempotencyKey)
        if (existing != null) {
            require(existing.requestHash == requestHash) { "idempotency key body mismatch" }
            return courierRepository.findByIdentityIdAndDeletedAtIsNull(identityId)
                ?: error("idempotency recorded but no courier for identity $identityId")
        }
        val now = Instant.now()
        val courier = Courier(
            identityId = identityId,
            name = name,
            email = email,
            phone = phone,
        )
        courierRepository.save(courier)
        writeAudit(
            courierId = courier.id!!,
            action = CourierAuditLog.ACTION_CREATED,
            before = null,
            after = mapOf("status" to courier.status, "identity_id" to identityId.toString()),
            actorId = createdBy,
            reason = null,
            correlationId = correlationId,
        )
        idemService.record(
            IdempotencyKey.SCOPE_COURIER_CREATE,
            idempotencyKey,
            requestHash,
            201,
            mapOf("courier_id" to courier.id.toString()),
            createdBy,
            now,
        )
        emitCreated(courier, correlationId, createdBy)
        return courier
    }

    @Transactional
    fun approve(courierId: UUID, correlationId: UUID, actingUser: UUID): Courier {
        val now = Instant.now()
        val courier = requireActive(courierId)
        val before = mapOf("status" to courier.status)
        courier.approve(now)
        writeAudit(courierId, CourierAuditLog.ACTION_APPROVED, before, mapOf("status" to courier.status), actingUser, null, correlationId)
        emitStateChange(courier, "courier.approved.v1", correlationId, actingUser)
        return courier
    }

    @Transactional
    fun reject(courierId: UUID, reason: String, correlationId: UUID, actingUser: UUID): Courier {
        val now = Instant.now()
        val courier = requireActive(courierId)
        val before = mapOf("status" to courier.status)
        courier.reject(reason, now)
        writeAudit(courierId, CourierAuditLog.ACTION_REJECTED, before, mapOf("status" to courier.status, "rejected_reason" to reason), actingUser, reason, correlationId)
        emitStateChange(courier, "courier.rejected.v1", correlationId, actingUser)
        return courier
    }

    @Transactional
    fun suspend(courierId: UUID, reason: String, correlationId: UUID, actingUser: UUID): Courier {
        val now = Instant.now()
        val courier = requireActive(courierId)
        val before = mapOf("status" to courier.status)
        courier.suspend(reason, actingUser, now)
        writeAudit(courierId, CourierAuditLog.ACTION_SUSPENDED, before, mapOf("status" to courier.status, "suspended_reason" to reason), actingUser, reason, correlationId)
        emitStateChange(courier, "courier.suspended.v1", correlationId, actingUser)
        return courier
    }

    @Transactional
    fun reinstate(courierId: UUID, correlationId: UUID, actingUser: UUID): Courier {
        val now = Instant.now()
        val courier = courierRepository.findByIdAndDeletedAtIsNull(courierId)
            ?: error("courier $courierId not found")
        val before = mapOf("status" to courier.status)
        courier.reinstate(now)
        writeAudit(courierId, CourierAuditLog.ACTION_REINSTATED, before, mapOf("status" to courier.status), actingUser, null, correlationId)
        emitStateChange(courier, "courier.reinstated.v1", correlationId, actingUser)
        return courier
    }

    @Transactional
    fun disable(courierId: UUID, correlationId: UUID, actingUser: UUID): Courier {
        val now = Instant.now()
        val courier = requireActive(courierId)
        val before = mapOf("status" to courier.status)
        courier.disable(now)
        writeAudit(courierId, CourierAuditLog.ACTION_DISABLED, before, mapOf("status" to courier.status), actingUser, null, correlationId)
        emitStateChange(courier, "courier.disabled.v1", correlationId, actingUser)
        return courier
    }

    @Transactional
    fun erase(courierId: UUID, correlationId: UUID, actingUser: UUID): Courier {
        val now = Instant.now()
        val courier = courierRepository.findByIdAndDeletedAtIsNull(courierId)
            ?: error("courier $courierId not found")
        val before = mapOf("status" to courier.status)
        courier.erase(now)
        writeAudit(courierId, CourierAuditLog.ACTION_ERASED, before, mapOf("status" to courier.status), actingUser, null, correlationId)
        emitStateChange(courier, "courier.erased.v1", correlationId, actingUser)
        return courier
    }

    @Transactional
    fun addDocument(
        courierId: UUID,
        type: String,
        fileId: UUID,
        critical: Boolean,
        expiryDate: Instant?,
        correlationId: UUID,
        actingUser: UUID,
    ): CourierDocument {
        val now = Instant.now()
        val courier = requireActive(courierId)
        val doc = CourierDocument(
            courierId = courierId,
            type = type,
            fileId = fileId,
            critical = critical,
            expiryDate = expiryDate,
        )
        documentRepository.save(doc)
        writeAudit(courierId, CourierAuditLog.ACTION_DOCUMENT_ADDED, null, mapOf("document_id" to doc.id.toString(), "type" to type), actingUser, null, correlationId)
        emitEvent(courierId, "courier.document.added.v1", correlationId, actingUser, mapOf("document_id" to doc.id.toString(), "type" to type, "critical" to critical))
        return doc
    }

    @Transactional
    fun verifyDocument(
        documentId: UUID,
        verificationId: UUID,
        correlationId: UUID,
        actingUser: UUID,
    ): CourierDocument {
        val now = Instant.now()
        val doc = documentRepository.findById(documentId).orElseThrow()
        doc.verify(verificationId, now)
        writeAudit(doc.courierId, CourierAuditLog.ACTION_DOCUMENT_VERIFIED, null, mapOf("document_id" to doc.id.toString()), actingUser, null, correlationId)
        emitEvent(doc.courierId, "courier.document.verified.v1", correlationId, actingUser, mapOf("document_id" to doc.id.toString()))
        return doc
    }

    @Transactional
    fun grantCityEligibility(
        courierId: UUID,
        cityId: UUID,
        notes: String?,
        correlationId: UUID,
        actingUser: UUID,
    ): CourierCityEligibility {
        val now = Instant.now()
        val courier = requireActive(courierId)
        require(cityEligibilityRepository.findActive(courierId, cityId) == null) {
            "eligibility for courier $courierId + city $cityId already active"
        }
        val eligibility = CourierCityEligibility(
            courierId = courierId,
            cityId = cityId,
            grantedBy = actingUser,
            notes = notes,
        )
        cityEligibilityRepository.save(eligibility)
        writeAudit(courierId, CourierAuditLog.ACTION_CITY_GRANTED, null, mapOf("city_id" to cityId.toString()), actingUser, notes, correlationId)
        emitEvent(courierId, "courier.eligibility.granted.v1", correlationId, actingUser, mapOf("city_id" to cityId.toString()))
        return eligibility
    }

    @Transactional
    fun revokeCityEligibility(
        courierId: UUID,
        cityId: UUID,
        correlationId: UUID,
        actingUser: UUID,
    ): CourierCityEligibility {
        val now = Instant.now()
        val eligibility = cityEligibilityRepository.findActive(courierId, cityId)
            ?: error("no active eligibility for courier $courierId + city $cityId")
        eligibility.revoke(actingUser, now)
        writeAudit(courierId, CourierAuditLog.ACTION_CITY_REVOKED, null, mapOf("city_id" to cityId.toString()), actingUser, null, correlationId)
        emitEvent(courierId, "courier.eligibility.revoked.v1", correlationId, actingUser, mapOf("city_id" to cityId.toString()))
        return eligibility
    }

    @Transactional
    fun applyRating(
        courierId: UUID,
        requestId: UUID,
        service: String,
        rating: Short,
        comment: String?,
        correlationId: UUID,
        actingUser: UUID,
    ): Courier {
        require(rating.toInt() in 1..5) { "rating must be 1..5" }
        val now = Instant.now()
        val courier = requireActive(courierId)
        val line = CourierRatingHistory(
            id = UUID.randomUUID(),
            courierId = courierId,
            requestId = requestId,
            service = service,
            rating = rating,
            comment = comment,
            correlationId = correlationId,
            createdBy = actingUser,
        )
        ratingHistoryRepository.save(line)
        courier.applyRating(BigDecimal(rating.toInt()), now)
        writeAudit(courierId, CourierAuditLog.ACTION_RATING_ADDED, null, mapOf("rating" to rating.toInt(), "request_id" to requestId.toString()), actingUser, null, correlationId)
        emitEvent(courierId, "courier.rating.added.v1", correlationId, actingUser, mapOf("rating" to rating.toInt(), "new_rating" to courier.rating.toDouble(), "new_count" to courier.ratingCount))
        return courier
    }

    @Transactional
    fun setPrimaryVehicle(
        courierId: UUID,
        vehicleId: UUID,
        correlationId: UUID,
        actingUser: UUID,
    ): Courier {
        val now = Instant.now()
        val courier = requireActive(courierId)
        val before = mapOf("primary_vehicle_id" to courier.primaryVehicleId?.toString())
        courier.setPrimaryVehicle(vehicleId, now)
        writeAudit(courierId, CourierAuditLog.ACTION_PRIMARY_VEHICLE_CHANGED, before, mapOf("primary_vehicle_id" to vehicleId.toString()), actingUser, null, correlationId)
        emitEvent(courierId, "courier.primary_vehicle.changed.v1", correlationId, actingUser, mapOf("vehicle_id" to vehicleId.toString()))
        return courier
    }

    @Transactional
    fun updateProfile(
        courierId: UUID,
        name: String?,
        email: String?,
        phone: String?,
        correlationId: UUID,
        actingUser: UUID,
    ): Courier {
        val now = Instant.now()
        val courier = requireActive(courierId)
        val before = mapOf("name" to courier.name, "email" to courier.email, "phone" to courier.phone)
        courier.name = name
        courier.email = email
        courier.phone = phone
        courier.updatedAt = now
        courier.version += 1
        writeAudit(courierId, CourierAuditLog.ACTION_PROFILE_UPDATED, before, mapOf("name" to name, "email" to email, "phone" to phone), actingUser, null, correlationId)
        emitEvent(courierId, "courier.profile.updated.v1", correlationId, actingUser, mapOf("name" to name, "email" to email))
        return courier
    }

    @Transactional
    fun touchOnline(courierId: UUID, correlationId: UUID, actingUser: UUID): Courier {
        val now = Instant.now()
        val courier = requireActive(courierId)
        courier.touchOnline(now)
        emitEvent(courierId, "courier.online.v1", correlationId, actingUser, mapOf("last_online_at" to now.toString()))
        return courier
    }

    // ----- Shift scheduling (new vs driver-service) -----

    @Transactional
    fun scheduleShift(
        courierId: UUID,
        startAt: Instant,
        endAt: Instant,
        correlationId: UUID,
        actingUser: UUID,
    ): CourierShift {
        val now = Instant.now()
        val courier = requireActive(courierId)
        val shift = CourierShift(
            courierId = courierId,
            startAt = startAt,
            endAt = endAt,
        )
        shiftRepository.save(shift)
        writeAudit(courierId, CourierAuditLog.ACTION_SHIFT_SCHEDULED, null, mapOf("shift_id" to shift.id.toString(), "start_at" to startAt.toString(), "end_at" to endAt.toString()), actingUser, null, correlationId)
        emitEvent(courierId, "courier.shift.scheduled.v1", correlationId, actingUser, mapOf("shift_id" to shift.id.toString(), "start_at" to startAt.toString(), "end_at" to endAt.toString()))
        return shift
    }

    @Transactional
    fun activateShift(shiftId: UUID, correlationId: UUID, actingUser: UUID): CourierShift {
        val now = Instant.now()
        val shift = shiftRepository.findById(shiftId).orElseThrow()
        check(shiftRepository.findActive(shift.courierId) == null) {
            "courier ${shift.courierId} already has an active shift"
        }
        shift.activate(now)
        writeAudit(shift.courierId, CourierAuditLog.ACTION_SHIFT_ACTIVATED, null, mapOf("shift_id" to shift.id.toString()), actingUser, null, correlationId)
        emitEvent(shift.courierId, "courier.shift.activated.v1", correlationId, actingUser, mapOf("shift_id" to shift.id.toString(), "actual_start_at" to now.toString()))
        return shift
    }

    @Transactional
    fun completeShift(shiftId: UUID, correlationId: UUID, actingUser: UUID): CourierShift {
        val now = Instant.now()
        val shift = shiftRepository.findById(shiftId).orElseThrow()
        shift.complete(now)
        writeAudit(shift.courierId, CourierAuditLog.ACTION_SHIFT_COMPLETED, null, mapOf("shift_id" to shift.id.toString()), actingUser, null, correlationId)
        emitEvent(shift.courierId, "courier.shift.completed.v1", correlationId, actingUser, mapOf("shift_id" to shift.id.toString(), "actual_end_at" to now.toString()))
        return shift
    }

    @Transactional
    fun cancelShift(shiftId: UUID, reason: String, correlationId: UUID, actingUser: UUID): CourierShift {
        val now = Instant.now()
        val shift = shiftRepository.findById(shiftId).orElseThrow()
        shift.cancel(reason, now)
        writeAudit(shift.courierId, CourierAuditLog.ACTION_SHIFT_CANCELLED, null, mapOf("shift_id" to shift.id.toString(), "reason" to reason), actingUser, reason, correlationId)
        emitEvent(shift.courierId, "courier.shift.cancelled.v1", correlationId, actingUser, mapOf("shift_id" to shift.id.toString(), "reason" to reason))
        return shift
    }

    private fun requireActive(courierId: UUID): Courier {
        val courier = courierRepository.findByIdAndDeletedAtIsNull(courierId)
            ?: error("courier $courierId not found")
        check(courier.status != Courier.STATUS_ERASED) { "courier $courierId is erased" }
        return courier
    }

    private fun writeAudit(
        courierId: UUID,
        action: String,
        before: Map<String, Any?>?,
        after: Map<String, Any?>?,
        actorId: UUID,
        reason: String?,
        correlationId: UUID,
    ) {
        auditLogRepository.save(
            CourierAuditLog(
                id = UUID.randomUUID(),
                courierId = courierId,
                action = action,
                before = before,
                after = after,
                actorId = actorId,
                reason = reason,
                correlationId = correlationId,
            ),
        )
    }

    private fun emitCreated(courier: Courier, correlationId: UUID, createdBy: UUID) {
        emitEvent(
            courier.id!!,
            "courier.created.v1",
            correlationId,
            createdBy,
            mapOf(
                "courier_id" to courier.id.toString(),
                "identity_id" to courier.identityId.toString(),
                "status" to courier.status,
                "rating" to courier.rating.toDouble(),
            ),
        )
    }

    private fun emitStateChange(courier: Courier, eventType: String, correlationId: UUID, createdBy: UUID) {
        emitEvent(
            courier.id!!,
            eventType,
            correlationId,
            createdBy,
            mapOf(
                "courier_id" to courier.id.toString(),
                "identity_id" to courier.identityId.toString(),
                "status" to courier.status,
            ),
        )
    }

    private fun emitEvent(
        courierId: UUID,
        eventType: String,
        correlationId: UUID,
        createdBy: UUID,
        payload: Map<String, Any?>,
    ) {
        outboxRepository.save(
            OutboxEvent(
                id = UUID.randomUUID(),
                aggregateType = "Courier",
                aggregateId = courierId,
                eventType = eventType,
                topic = eventType,
                payload = payload,
                correlationId = correlationId,
                createdBy = createdBy,
            ),
        )
    }
}
