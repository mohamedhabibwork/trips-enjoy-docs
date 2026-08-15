package com.trips_enjoy.courier.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.courier.application.CourierWriteService
import com.trips_enjoy.courier.domain.Courier
import com.trips_enjoy.courier.domain.CourierCityEligibilityRepository
import com.trips_enjoy.courier.domain.CourierDocument
import com.trips_enjoy.courier.domain.CourierDocumentRepository
import com.trips_enjoy.courier.domain.CourierRepository
import com.trips_enjoy.courier.domain.CourierShift
import com.trips_enjoy.courier.domain.CourierShiftRepository
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
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
@RequestMapping("/v1/couriers")
class CourierController(
    private val writeService: CourierWriteService,
    private val courierRepository: CourierRepository,
    private val documentRepository: CourierDocumentRepository,
    private val shiftRepository: CourierShiftRepository,
    private val cityEligibilityRepository: CourierCityEligibilityRepository,
    private val objectMapper: ObjectMapper,
) {

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_courier.write') or hasAuthority('SCOPE_courier.admin')")
    fun create(
        @Valid @RequestBody req: CreateCourierRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): ResponseEntity<CourierResponse> {
        val requestHash = sha256(objectMapper.writeValueAsString(req))
        val correlationId = req.correlationId?.let(UUID::fromString) ?: UUID.randomUUID()
        val courier = writeService.create(
            identityId = req.identityIdAsUuid(),
            name = req.name,
            email = req.email,
            phone = req.phone,
            correlationId = correlationId,
            createdBy = UUID.fromString(actingUser),
            idempotencyKey = idempotencyKey,
            requestHash = requestHash,
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(courier.toResponse())
    }

    @GetMapping("/{courier_id}")
    @PreAuthorize("isAuthenticated()")
    fun get(@PathVariable("courier_id") courierId: String): CourierResponse =
        courierRepository.findByIdAndDeletedAtIsNull(UUID.fromString(courierId))
            ?.toResponse() ?: throw NoSuchElementException("courier $courierId not found")

    @PatchMapping("/{courier_id}")
    @PreAuthorize("hasAuthority('SCOPE_courier.write') or hasAuthority('SCOPE_courier.admin')")
    fun update(
        @PathVariable("courier_id") courierId: String,
        @Valid @RequestBody req: UpdateProfileRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): CourierResponse {
        val courier = writeService.updateProfile(
            courierId = UUID.fromString(courierId),
            name = req.name,
            email = req.email,
            phone = req.phone,
            correlationId = UUID.randomUUID(),
            actingUser = UUID.fromString(actingUser),
        )
        return courier.toResponse()
    }

    @PostMapping("/{courier_id}/approve")
    @PreAuthorize("hasAuthority('SCOPE_courier.admin')")
    fun approve(
        @PathVariable("courier_id") courierId: String,
        @RequestHeader("X-User-Id") actingUser: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
    ): CourierResponse =
        writeService.approve(
            courierId = UUID.fromString(courierId),
            correlationId = UUID.randomUUID(),
            actingUser = UUID.fromString(actingUser),
        ).toResponse()

    @PostMapping("/{courier_id}/reject")
    @PreAuthorize("hasAuthority('SCOPE_courier.admin')")
    fun reject(
        @PathVariable("courier_id") courierId: String,
        @Valid @RequestBody req: RejectRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): CourierResponse =
        writeService.reject(
            courierId = UUID.fromString(courierId),
            reason = req.reason,
            correlationId = UUID.randomUUID(),
            actingUser = UUID.fromString(actingUser),
        ).toResponse()

    @PostMapping("/{courier_id}/suspend")
    @PreAuthorize("hasAuthority('SCOPE_courier.admin')")
    fun suspend(
        @PathVariable("courier_id") courierId: String,
        @Valid @RequestBody req: SuspendRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): CourierResponse =
        writeService.suspend(
            courierId = UUID.fromString(courierId),
            reason = req.reason,
            correlationId = UUID.randomUUID(),
            actingUser = UUID.fromString(actingUser),
        ).toResponse()

    @PostMapping("/{courier_id}/reinstate")
    @PreAuthorize("hasAuthority('SCOPE_courier.admin')")
    fun reinstate(
        @PathVariable("courier_id") courierId: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): CourierResponse =
        writeService.reinstate(
            courierId = UUID.fromString(courierId),
            correlationId = UUID.randomUUID(),
            actingUser = UUID.fromString(actingUser),
        ).toResponse()

    @PostMapping("/{courier_id}/disable")
    @PreAuthorize("hasAuthority('SCOPE_courier.admin')")
    fun disable(
        @PathVariable("courier_id") courierId: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): CourierResponse =
        writeService.disable(
            courierId = UUID.fromString(courierId),
            correlationId = UUID.randomUUID(),
            actingUser = UUID.fromString(actingUser),
        ).toResponse()

    @PostMapping("/{courier_id}/erase")
    @PreAuthorize("hasAuthority('SCOPE_courier.admin')")
    fun erase(
        @PathVariable("courier_id") courierId: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): ResponseEntity<Void> {
        writeService.erase(
            courierId = UUID.fromString(courierId),
            correlationId = UUID.randomUUID(),
            actingUser = UUID.fromString(actingUser),
        )
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/{courier_id}/documents")
    @PreAuthorize("isAuthenticated()")
    fun listDocuments(@PathVariable("courier_id") courierId: String): List<DocumentResponse> =
        documentRepository.findByCourierIdAndDeletedAtIsNull(UUID.fromString(courierId))
            .map { it.toResponse() }

    @PostMapping("/{courier_id}/documents")
    @PreAuthorize("hasAuthority('SCOPE_courier.write') or hasAuthority('SCOPE_courier.admin')")
    fun addDocument(
        @PathVariable("courier_id") courierId: String,
        @Valid @RequestBody req: AddDocumentRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): ResponseEntity<DocumentResponse> {
        val doc = writeService.addDocument(
            courierId = UUID.fromString(courierId),
            type = req.type,
            fileId = UUID.fromString(req.fileId),
            critical = req.critical,
            expiryDate = req.expiryDate,
            correlationId = UUID.randomUUID(),
            actingUser = UUID.fromString(actingUser),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(doc.toResponse())
    }

    @PostMapping("/{courier_id}/documents/{document_id}/verify")
    @PreAuthorize("hasAuthority('SCOPE_courier.admin')")
    fun verifyDocument(
        @PathVariable("courier_id") courierId: String,
        @PathVariable("document_id") documentId: String,
        @Valid @RequestBody req: VerifyDocumentRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): DocumentResponse =
        writeService.verifyDocument(
            documentId = UUID.fromString(documentId),
            verificationId = UUID.fromString(req.verificationId),
            correlationId = UUID.randomUUID(),
            actingUser = UUID.fromString(actingUser),
        ).toResponse()

    @DeleteMapping("/{courier_id}/documents/{document_id}")
    @PreAuthorize("hasAuthority('SCOPE_courier.admin')")
    fun deleteDocument(
        @PathVariable("courier_id") courierId: String,
        @PathVariable("document_id") documentId: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): ResponseEntity<Void> {
        val doc = documentRepository.findById(UUID.fromString(documentId)).orElseThrow()
        doc.deletedAt = Instant.now()
        doc.updatedBy = UUID.fromString(actingUser)
        doc.updatedAt = Instant.now()
        documentRepository.save(doc)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/{courier_id}/eligibility")
    @PreAuthorize("isAuthenticated()")
    fun listEligibility(@PathVariable("courier_id") courierId: String): List<EligibilityResponse> =
        cityEligibilityRepository.findByCourierIdAndRevokedAtIsNull(UUID.fromString(courierId))
            .map { it.toResponse() }

    @PostMapping("/{courier_id}/eligibility/cities/{city_id}")
    @PreAuthorize("hasAuthority('SCOPE_courier.admin')")
    fun grantEligibility(
        @PathVariable("courier_id") courierId: String,
        @PathVariable("city_id") cityId: String,
        @Valid @RequestBody req: GrantCityRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): ResponseEntity<EligibilityResponse> {
        val eligibility = writeService.grantCityEligibility(
            courierId = UUID.fromString(courierId),
            cityId = UUID.fromString(req.cityId),
            notes = req.notes,
            correlationId = UUID.randomUUID(),
            actingUser = UUID.fromString(actingUser),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(eligibility.toResponse())
    }

    @PostMapping("/{courier_id}/rating")
    @PreAuthorize("hasAuthority('SCOPE_food_order.write')")
    fun applyRating(
        @PathVariable("courier_id") courierId: String,
        @Valid @RequestBody req: RatingRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): RatingResponse {
        val courier = writeService.applyRating(
            courierId = UUID.fromString(courierId),
            requestId = req.requestIdAsUuid(),
            service = req.service,
            rating = req.rating,
            comment = req.comment,
            correlationId = UUID.randomUUID(),
            actingUser = UUID.fromString(actingUser),
        )
        return RatingResponse(courier.id.toString(), courier.rating.toDouble(), courier.ratingCount)
    }

    @PostMapping("/{courier_id}/online")
    @PreAuthorize("isAuthenticated()")
    fun touchOnline(
        @PathVariable("courier_id") courierId: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): CourierResponse =
        writeService.touchOnline(
            courierId = UUID.fromString(courierId),
            correlationId = UUID.randomUUID(),
            actingUser = UUID.fromString(actingUser),
        ).toResponse()

    // ---- Shift scheduling (new vs driver-service) ----

    @PostMapping("/{courier_id}/shifts")
    @PreAuthorize("hasAuthority('SCOPE_courier.admin')")
    fun scheduleShift(
        @PathVariable("courier_id") courierId: String,
        @Valid @RequestBody req: ScheduleShiftRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): ResponseEntity<ShiftResponse> {
        val shift = writeService.scheduleShift(
            courierId = UUID.fromString(courierId),
            startAt = req.startAtAsInstant(),
            endAt = req.endAtAsInstant(),
            correlationId = UUID.randomUUID(),
            actingUser = UUID.fromString(actingUser),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(shift.toResponse())
    }

    @PostMapping("/{courier_id}/shifts/{shift_id}/activate")
    @PreAuthorize("hasAuthority('SCOPE_courier.admin')")
    fun activateShift(
        @PathVariable("courier_id") courierId: String,
        @PathVariable("shift_id") shiftId: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): ShiftResponse =
        writeService.activateShift(
            shiftId = UUID.fromString(shiftId),
            correlationId = UUID.randomUUID(),
            actingUser = UUID.fromString(actingUser),
        ).toResponse()

    @PostMapping("/{courier_id}/shifts/{shift_id}/complete")
    @PreAuthorize("hasAuthority('SCOPE_courier.admin')")
    fun completeShift(
        @PathVariable("courier_id") courierId: String,
        @PathVariable("shift_id") shiftId: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): ShiftResponse =
        writeService.completeShift(
            shiftId = UUID.fromString(shiftId),
            correlationId = UUID.randomUUID(),
            actingUser = UUID.fromString(actingUser),
        ).toResponse()

    @PostMapping("/{courier_id}/shifts/{shift_id}/cancel")
    @PreAuthorize("hasAuthority('SCOPE_courier.admin')")
    fun cancelShift(
        @PathVariable("courier_id") courierId: String,
        @PathVariable("shift_id") shiftId: String,
        @Valid @RequestBody req: CancelShiftRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): ShiftResponse =
        writeService.cancelShift(
            shiftId = UUID.fromString(shiftId),
            reason = req.reason,
            correlationId = UUID.randomUUID(),
            actingUser = UUID.fromString(actingUser),
        ).toResponse()

    private fun sha256(payload: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(payload.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

private fun Courier.toResponse() = CourierResponse(
    courierId = id.toString(),
    identityId = identityId.toString(),
    status = status,
    rating = rating.toDouble(),
    ratingCount = ratingCount,
    primaryVehicleId = primaryVehicleId?.toString(),
    kycVerifiedAt = kycVerifiedAt?.toString(),
    lastOnlineAt = lastOnlineAt?.toString(),
)

private fun CourierDocument.toResponse() = DocumentResponse(
    documentId = id.toString(),
    courierId = courierId.toString(),
    type = type,
    fileId = fileId.toString(),
    verificationId = verificationId?.toString(),
    verifiedAt = verifiedAt?.toString(),
    expiryDate = expiryDate?.toString(),
    critical = critical,
    status = status,
)

private fun com.trips_enjoy.courier.domain.CourierCityEligibility.toResponse() = EligibilityResponse(
    eligibilityId = id.toString(),
    courierId = courierId.toString(),
    cityId = cityId.toString(),
    grantedAt = grantedAt.toString(),
    revokedAt = revokedAt?.toString(),
    notes = notes,
)

private fun CourierShift.toResponse() = ShiftResponse(
    shiftId = id.toString(),
    courierId = courierId.toString(),
    startAt = startAt.toString(),
    endAt = endAt.toString(),
    actualStartAt = actualStartAt?.toString(),
    actualEndAt = actualEndAt?.toString(),
    status = status,
)