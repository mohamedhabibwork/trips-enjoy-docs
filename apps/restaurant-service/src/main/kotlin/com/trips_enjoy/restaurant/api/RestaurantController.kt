package com.trips_enjoy.restaurant.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.restaurant.application.RestaurantWriteService
import com.trips_enjoy.restaurant.domain.Restaurant
import com.trips_enjoy.restaurant.domain.RestaurantRepository
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.util.UUID

@RestController
@RequestMapping("/v1/restaurants")
class RestaurantController(
    private val writeService: RestaurantWriteService,
    private val restaurantRepository: RestaurantRepository,
    private val objectMapper: ObjectMapper,
) {

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_restaurant.write') or hasAuthority('SCOPE_restaurant.admin')")
    fun create(
        @Valid @RequestBody req: CreateRestaurantRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): ResponseEntity<RestaurantResponse> {
        val requestHash = sha256(objectMapper.writeValueAsString(req))
        val correlationId = req.correlationId?.let(UUID::fromString) ?: UUID.randomUUID()
        val restaurant = writeService.create(
            merchantId = req.merchantIdAsUuid(),
            name = req.name,
            slug = req.slug,
            type = req.type,
            description = req.description,
            correlationId = correlationId,
            createdBy = UUID.fromString(actingUser),
            idempotencyKey = idempotencyKey,
            requestHash = requestHash,
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(restaurant.toResponse())
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    fun get(@PathVariable("id") id: String): RestaurantResponse =
        restaurantRepository.findByIdAndDeletedAtIsNull(UUID.fromString(id))
            ?.toResponse() ?: throw NoSuchElementException("restaurant $id not found")

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_restaurant.write') or hasAuthority('SCOPE_restaurant.admin')")
    fun update(
        @PathVariable("id") id: String,
        @Valid @RequestBody req: UpdateRestaurantRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): RestaurantResponse =
        writeService.update(
            restaurantId = UUID.fromString(id),
            name = req.name,
            description = req.description,
            logoFileId = req.logoFileId?.let(UUID::fromString),
            coverFileId = req.coverFileId?.let(UUID::fromString),
            autoOfflineEnabled = req.autoOfflineEnabled,
            correlationId = UUID.randomUUID(),
            actingUser = UUID.fromString(actingUser),
        ).toResponse()

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('SCOPE_restaurant.write')")
    fun submit(
        @PathVariable("id") id: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): RestaurantResponse =
        writeService.submit(
            restaurantId = UUID.fromString(id),
            correlationId = UUID.randomUUID(),
            actingUser = UUID.fromString(actingUser),
        ).toResponse()

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('SCOPE_restaurant.admin')")
    fun approve(
        @PathVariable("id") id: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): RestaurantResponse =
        writeService.approve(
            restaurantId = UUID.fromString(id),
            correlationId = UUID.randomUUID(),
            actingUser = UUID.fromString(actingUser),
        ).toResponse()

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('SCOPE_restaurant.admin')")
    fun reject(
        @PathVariable("id") id: String,
        @Valid @RequestBody req: RejectRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): RestaurantResponse =
        writeService.reject(
            restaurantId = UUID.fromString(id),
            reason = req.reason,
            correlationId = UUID.randomUUID(),
            actingUser = UUID.fromString(actingUser),
        ).toResponse()

    @PostMapping("/{id}/online")
    @PreAuthorize("hasAuthority('SCOPE_restaurant.write')")
    fun goOnline(
        @PathVariable("id") id: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): RestaurantResponse =
        writeService.goOnline(
            restaurantId = UUID.fromString(id),
            correlationId = UUID.randomUUID(),
            actingUser = UUID.fromString(actingUser),
        ).toResponse()

    @PostMapping("/{id}/offline")
    @PreAuthorize("hasAuthority('SCOPE_restaurant.write')")
    fun goOffline(
        @PathVariable("id") id: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): RestaurantResponse =
        writeService.goOffline(
            restaurantId = UUID.fromString(id),
            correlationId = UUID.randomUUID(),
            actingUser = UUID.fromString(actingUser),
        ).toResponse()

    @PostMapping("/{id}/suspend")
    @PreAuthorize("hasAuthority('SCOPE_restaurant.admin')")
    fun suspend(
        @PathVariable("id") id: String,
        @Valid @RequestBody req: SuspendRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): RestaurantResponse =
        writeService.suspend(
            restaurantId = UUID.fromString(id),
            reason = req.reason,
            correlationId = UUID.randomUUID(),
            actingUser = UUID.fromString(actingUser),
        ).toResponse()

    @PostMapping("/{id}/reinstate")
    @PreAuthorize("hasAuthority('SCOPE_restaurant.admin')")
    fun reinstate(
        @PathVariable("id") id: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): RestaurantResponse =
        writeService.reinstate(
            restaurantId = UUID.fromString(id),
            correlationId = UUID.randomUUID(),
            actingUser = UUID.fromString(actingUser),
        ).toResponse()

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAuthority('SCOPE_restaurant.write')")
    fun close(
        @PathVariable("id") id: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): RestaurantResponse =
        writeService.close(
            restaurantId = UUID.fromString(id),
            correlationId = UUID.randomUUID(),
            actingUser = UUID.fromString(actingUser),
        ).toResponse()

    @PostMapping("/{id}/resubmit")
    @PreAuthorize("hasAuthority('SCOPE_restaurant.write')")
    fun resubmit(
        @PathVariable("id") id: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): RestaurantResponse =
        writeService.resubmit(
            restaurantId = UUID.fromString(id),
            correlationId = UUID.randomUUID(),
            actingUser = UUID.fromString(actingUser),
        ).toResponse()

    @PostMapping("/{id}/cuisines")
    @PreAuthorize("hasAuthority('SCOPE_restaurant.write')")
    fun addCuisine(
        @PathVariable("id") id: String,
        @Valid @RequestBody req: AddCuisineRequest,
        @RequestHeader("X-User-Id") actingUser: String,
    ): ResponseEntity<Map<String, Any?>> {
        writeService.addCuisine(
            restaurantId = UUID.fromString(id),
            cuisine = req.cuisine,
            correlationId = UUID.randomUUID(),
            actingUser = UUID.fromString(actingUser),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(mapOf("cuisine" to req.cuisine))
    }

    @PostMapping("/{id}/tags")
    @PreAuthorize("hasAuthority('SCOPE_restaurant.write')")
    fun addTag(
        @PathVariable("id") id: String,
        @Valid @RequestBody req: AddTagRequest,
        @RequestHeader("X-User-Id") actingUser: String,
    ): ResponseEntity<Map<String, Any?>> {
        writeService.addTag(
            restaurantId = UUID.fromString(id),
            tag = req.tag,
            correlationId = UUID.randomUUID(),
            actingUser = UUID.fromString(actingUser),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(mapOf("tag" to req.tag))
    }

    private fun sha256(payload: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(payload.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

private fun Restaurant.toResponse() = RestaurantResponse(
    restaurantId = id.toString(),
    merchantId = merchantId.toString(),
    name = name,
    slug = slug,
    type = type,
    state = state,
    online = online,
    avgRating = avgRating.toDouble(),
    reviewCount = reviewCount,
)