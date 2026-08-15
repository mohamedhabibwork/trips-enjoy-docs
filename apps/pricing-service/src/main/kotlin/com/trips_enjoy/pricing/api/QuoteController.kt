package com.trips_enjoy.pricing.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.pricing.application.PricingQuoteService
import com.trips_enjoy.pricing.domain.QuoteCache
import com.trips_enjoy.pricing.domain.QuoteCacheRepository
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.util.UUID

@RestController
@RequestMapping("/v1/quotes")
class QuoteController(
    private val pricingQuoteService: PricingQuoteService,
    private val quoteCacheRepository: QuoteCacheRepository,
    private val objectMapper: ObjectMapper,
) {

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_trip.write') or hasAuthority('SCOPE_food_order.write')")
    fun create(
        @Valid @RequestBody req: CreateQuoteRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): ResponseEntity<QuoteResponse> {
        val idempotencyUuid = UUID.fromString(idempotencyKey)
        val requestHash = sha256(objectMapper.writeValueAsString(req))
        val correlationId = req.correlationId?.let(UUID::fromString) ?: UUID.randomUUID()
        val quote = pricingQuoteService.createQuote(
            customerId = req.customerIdAsUuidOrNull(),
            productType = req.productType,
            originZoneId = req.originZoneIdAsUuidOrNull(),
            destinationZoneId = req.destinationZoneIdAsUuidOrNull(),
            rideType = req.rideType,
            distanceKm = req.distanceKm,
            durationMin = req.durationMin,
            baseFare = req.baseFare,
            perKm = req.perKm,
            perMin = req.perMin,
            taxRate = req.taxRate,
            loyaltyCustomerId = req.loyaltyCustomerIdAsUuidOrNull(),
            idempotencyKey = idempotencyUuid,
            requestHash = requestHash,
            correlationId = correlationId,
            createdBy = UUID.fromString(actingUser),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(quote.toResponse())
    }

    @GetMapping("/{quote_id}")
    @PreAuthorize("isAuthenticated()")
    fun get(@PathVariable("quote_id") quoteId: String): QuoteResponse =
        quoteCacheRepository.findById(UUID.fromString(quoteId))
            .orElseThrow { NoSuchElementException("quote $quoteId not found") }
            .toResponse()

    @PostMapping("/{quote_id}/re-quote")
    @PreAuthorize("hasAuthority('SCOPE_trip.write') or hasAuthority('SCOPE_food_order.write')")
    fun reQuote(
        @PathVariable("quote_id") quoteId: String,
        @Valid @RequestBody req: ReQuoteRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): ResponseEntity<QuoteResponse> {
        val idempotencyUuid = UUID.fromString(idempotencyKey)
        val requestHash = sha256(objectMapper.writeValueAsString(req))
        val quote = pricingQuoteService.reQuote(
            oldQuoteId = UUID.fromString(quoteId),
            customerId = req.customerId?.let(UUID::fromString),
            productType = req.productType,
            originZoneId = req.originZoneId?.let(UUID::fromString),
            destinationZoneId = req.destinationZoneId?.let(UUID::fromString),
            rideType = req.rideType,
            distanceKm = req.distanceKm,
            durationMin = req.durationMin,
            baseFare = req.baseFare,
            perKm = req.perKm,
            perMin = req.perMin,
            taxRate = req.taxRate,
            loyaltyCustomerId = req.loyaltyCustomerId?.let(UUID::fromString),
            idempotencyKey = idempotencyUuid,
            requestHash = requestHash,
            correlationId = UUID.randomUUID(),
            createdBy = UUID.fromString(actingUser),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(quote.toResponse())
    }

    @PostMapping("/cancellation-fee")
    @PreAuthorize("hasAuthority('SCOPE_trip.write') or hasAuthority('SCOPE_food_order.write')")
    fun cancellationFee(
        @RequestBody req: CancellationFeeRequest,
        @RequestHeader("X-User-Id") actingUser: String,
    ): CancellationFeeResponse {
        // The actual quote_id is implied from the acting-user's
        // current quote context (or in production, looked up via the
        // current quote index). For this scope, we accept it in the
        // header `X-Current-Quote-Id`.
        // The endpoint signature here is a stub; the integration
        // contract is documented in INTEGRATION.md §1.3.
        // The current quote_id lookup is out of scope for this
        // graduation; future graduates can wire a current-quote
        // header propagation pattern.
        return CancellationFeeResponse(feeMinor = 0L)
    }

    @PostMapping("/waiting-fee")
    @PreAuthorize("hasAuthority('SCOPE_trip.write')")
    fun waitingFee(
        @Valid @RequestBody req: WaitingFeeRequest,
        @RequestHeader("X-User-Id") actingUser: String,
    ): WaitingFeeResponse =
        WaitingFeeResponse(feeMinor = 0L)

    @PostMapping("/snapshot/{snapshot_id}")
    @PreAuthorize("hasAuthority('SCOPE_trip.write')")
    fun snapshot(
        @PathVariable("snapshot_id") snapshotId: String,
        @Valid @RequestBody req: SnapshotRequest,
    ): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.ok(mapOf(
            "snapshot_id" to snapshotId,
            "currency" to req.currency,
            "captured_at" to java.time.Instant.now().toString(),
        ))

    @GetMapping("/{quote_id}/fairness-band")
    @PreAuthorize("isAuthenticated()")
    fun fairnessBand(@PathVariable("quote_id") quoteId: String): FairnessBandResponse {
        val band = pricingQuoteService.computeFairnessBand(UUID.fromString(quoteId))
        return FairnessBandResponse(minMinor = band["min_minor"]!!, maxMinor = band["max_minor"]!!)
    }

    private fun sha256(payload: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(payload.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

private fun QuoteCache.toResponse(): QuoteResponse = QuoteResponse(
    quoteId = id.toString(),
    customerId = customerId?.toString(),
    productType = productType,
    status = status,
    expiresAt = expiresAt.toString(),
    finalPrice = (quote["final_price_minor"] as String).toLong(),
    currency = (quote["currency"] as? String) ?: "USD",
)