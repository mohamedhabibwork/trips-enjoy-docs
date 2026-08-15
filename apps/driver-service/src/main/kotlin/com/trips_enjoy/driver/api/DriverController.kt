package com.trips_enjoy.driver.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.driver.application.DriverWriteService
import com.trips_enjoy.driver.domain.Driver
import com.trips_enjoy.driver.domain.DriverDocument
import com.trips_enjoy.driver.domain.DriverDocumentRepository
import com.trips_enjoy.driver.domain.DriverCityEligibilityRepository
import com.trips_enjoy.driver.domain.DriverRepository
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
@RequestMapping("/v1/drivers")
class DriverController(
    private val writeService: DriverWriteService,
    private val driverRepository: DriverRepository,
    private val documentRepository: DriverDocumentRepository,
    private val cityEligibilityRepository: DriverCityEligibilityRepository,
    private val objectMapper: ObjectMapper,
) {

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_driver.write') or hasAuthority('SCOPE_driver.admin')")
    fun create(
        @Valid @RequestBody req: CreateDriverRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): ResponseEntity<DriverResponse> {
        val requestHash = sha256(objectMapper.writeValueAsString(req))
        val correlationId = req.correlationId?.let(UUID::fromString) ?: UUID.randomUUID()
        val driver = writeService.create(
            identityId = req.identityIdAsUuid(),
            name = req.name,
            email = req.email,
            phone = req.phone,
            correlationId = correlationId,
            createdBy = UUID.fromString(actingUser),
            idempotencyKey = idempotencyKey,
            requestHash = requestHash,
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(driver.toResponse())
    }

    @GetMapping("/{driver_id}")
    @PreAuthorize("isAuthenticated()")
    fun get(@PathVariable("driver_id") driverId: String): DriverResponse =
        driverRepository.findByIdAndDeletedAtIsNull(UUID.fromString(driverId))
            ?.toResponse() ?: throw NoSuchElementException("driver $driverId not found")

    @PatchMapping("/{driver_id}")
    @PreAuthorize("hasAuthority('SCOPE_driver.write') or hasAuthority('SCOPE_driver.admin')")
    fun update(
        @PathVariable("driver_id") driverId: String,
        @Valid @RequestBody req: UpdateProfileRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): DriverResponse {
        val correlationId = UUID.randomUUID()
        val driver = writeService.updateProfile(
            driverId = UUID.fromString(driverId),
            name = req.name,
            email = req.email,
            phone = req.phone,
            correlationId = correlationId,
            actingUser = UUID.fromString(actingUser),
        )
        return driver.toResponse()
    }

    @PostMapping("/{driver_id}/approve")
    @PreAuthorize("hasAuthority('SCOPE_driver.admin')")
    fun approve(
        @PathVariable("driver_id") driverId: String,
        @RequestHeader("X-User-Id") actingUser: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
    ): DriverResponse =
        writeService.approve(
            driverId = UUID.fromString(driverId),
            correlationId = UUID.randomUUID(),
            actingUser = UUID.fromString(actingUser),
        ).toResponse()

    @PostMapping("/{driver_id}/reject")
    @PreAuthorize("hasAuthority('SCOPE_driver.admin')")
    fun reject(
        @PathVariable("driver_id") driverId: String,
        @Valid @RequestBody req: RejectRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): DriverResponse =
        writeService.reject(
            driverId = UUID.fromString(driverId),
            reason = req.reason,
            correlationId = UUID.randomUUID(),
            actingUser = UUID.fromString(actingUser),
        ).toResponse()

    @PostMapping("/{driver_id}/suspend")
    @PreAuthorize("hasAuthority('SCOPE_driver.admin')")
    fun suspend(
        @PathVariable("driver_id") driverId: String,
        @Valid @RequestBody req: SuspendRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): DriverResponse =
        writeService.suspend(
            driverId = UUID.fromString(driverId),
            reason = req.reason,
            correlationId = UUID.randomUUID(),
            actingUser = UUID.fromString(actingUser),
        ).toResponse()

    @PostMapping("/{driver_id}/reinstate")
    @PreAuthorize("hasAuthority('SCOPE_driver.admin')")
    fun reinstate(
        @PathVariable("driver_id") driverId: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): DriverResponse =
        writeService.reinstate(
            driverId = UUID.fromString(driverId),
            correlationId = UUID.randomUUID(),
            actingUser = UUID.fromString(actingUser),
        ).toResponse()

    @PostMapping("/{driver_id}/disable")
    @PreAuthorize("hasAuthority('SCOPE_driver.admin')")
    fun disable(
        @PathVariable("driver_id") driverId: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): DriverResponse =
        writeService.disable(
            driverId = UUID.fromString(driverId),
            correlationId = UUID.randomUUID(),
            actingUser = UUID.fromString(actingUser),
        ).toResponse()

    @PostMapping("/{driver_id}/erase")
    @PreAuthorize("hasAuthority('SCOPE_driver.admin')")
    fun erase(
        @PathVariable("driver_id") driverId: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): ResponseEntity<Void> {
        writeService.erase(
            driverId = UUID.fromString(driverId),
            correlationId = UUID.randomUUID(),
            actingUser = UUID.fromString(actingUser),
        )
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/{driver_id}/documents")
    @PreAuthorize("isAuthenticated()")
    fun listDocuments(@PathVariable("driver_id") driverId: String): List<DocumentResponse> =
        documentRepository.findByDriverIdAndDeletedAtIsNull(UUID.fromString(driverId))
            .map { it.toResponse() }

    @PostMapping("/{driver_id}/documents")
    @PreAuthorize("hasAuthority('SCOPE_driver.write') or hasAuthority('SCOPE_driver.admin')")
    fun addDocument(
        @PathVariable("driver_id") driverId: String,
        @Valid @RequestBody req: AddDocumentRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): ResponseEntity<DocumentResponse> {
        val doc = writeService.addDocument(
            driverId = UUID.fromString(driverId),
            type = req.type,
            fileId = UUID.fromString(req.fileId),
            critical = req.critical,
            expiryDate = req.expiryDate,
            correlationId = UUID.randomUUID(),
            actingUser = UUID.fromString(actingUser),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(doc.toResponse())
    }

    @PostMapping("/{driver_id}/documents/{document_id}/verify")
    @PreAuthorize("hasAuthority('SCOPE_driver.admin')")
    fun verifyDocument(
        @PathVariable("driver_id") driverId: String,
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

    @DeleteMapping("/{driver_id}/documents/{document_id}")
    @PreAuthorize("hasAuthority('SCOPE_driver.admin')")
    fun deleteDocument(
        @PathVariable("driver_id") driverId: String,
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

    @GetMapping("/{driver_id}/eligibility")
    @PreAuthorize("isAuthenticated()")
    fun listEligibility(@PathVariable("driver_id") driverId: String): List<EligibilityResponse> =
        cityEligibilityRepository.findByDriverIdAndRevokedAtIsNull(UUID.fromString(driverId))
            .map { it.toResponse() }

    @PostMapping("/{driver_id}/eligibility/cities/{city_id}")
    @PreAuthorize("hasAuthority('SCOPE_driver.admin')")
    fun grantEligibility(
        @PathVariable("driver_id") driverId: String,
        @PathVariable("city_id") cityId: String,
        @Valid @RequestBody req: GrantCityRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): ResponseEntity<EligibilityResponse> {
        val eligibility = writeService.grantCityEligibility(
            driverId = UUID.fromString(driverId),
            cityId = UUID.fromString(req.cityId),
            notes = req.notes,
            correlationId = UUID.randomUUID(),
            actingUser = UUID.fromString(actingUser),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(eligibility.toResponse())
    }

    @PostMapping("/{driver_id}/rating")
    @PreAuthorize("hasAuthority('SCOPE_trip.write') or hasAuthority('SCOPE_food_order.write')")
    fun applyRating(
        @PathVariable("driver_id") driverId: String,
        @Valid @RequestBody req: RatingRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): RatingResponse {
        val driver = writeService.applyRating(
            driverId = UUID.fromString(driverId),
            requestId = req.requestIdAsUuid(),
            service = req.service,
            rating = req.rating,
            comment = req.comment,
            correlationId = UUID.randomUUID(),
            actingUser = UUID.fromString(actingUser),
        )
        return RatingResponse(driver.id.toString(), driver.rating.toDouble(), driver.ratingCount)
    }

    @PostMapping("/{driver_id}/online")
    @PreAuthorize("isAuthenticated()")
    fun touchOnline(
        @PathVariable("driver_id") driverId: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): DriverResponse =
        writeService.touchOnline(
            driverId = UUID.fromString(driverId),
            correlationId = UUID.randomUUID(),
            actingUser = UUID.fromString(actingUser),
        ).toResponse()

    private fun sha256(payload: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(payload.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

private fun Driver.toResponse() = DriverResponse(
    driverId = id.toString(),
    identityId = identityId.toString(),
    status = status,
    rating = rating.toDouble(),
    ratingCount = ratingCount,
    primaryVehicleId = primaryVehicleId?.toString(),
    kycVerifiedAt = kycVerifiedAt?.toString(),
    lastOnlineAt = lastOnlineAt?.toString(),
)

private fun DriverDocument.toResponse() = DocumentResponse(
    documentId = id.toString(),
    driverId = driverId.toString(),
    type = type,
    fileId = fileId.toString(),
    verificationId = verificationId?.toString(),
    verifiedAt = verifiedAt?.toString(),
    expiryDate = expiryDate?.toString(),
    critical = critical,
    status = status,
)

private fun com.trips_enjoy.driver.domain.DriverCityEligibility.toResponse() = EligibilityResponse(
    eligibilityId = id.toString(),
    driverId = driverId.toString(),
    cityId = cityId.toString(),
    grantedAt = grantedAt.toString(),
    revokedAt = revokedAt?.toString(),
    notes = notes,
)