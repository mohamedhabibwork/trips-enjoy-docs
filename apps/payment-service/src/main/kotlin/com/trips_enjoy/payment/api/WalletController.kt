package com.trips_enjoy.payment.api

import com.trips_enjoy.payment.application.WalletService
import com.trips_enjoy.payment.domain.Wallet
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
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

/**
 * The wallet REST controller. Implements
 * docs/services/payment-service/INTEGRATION.md §2 (wallet endpoints):
 *   * GET /v1/wallets/{id}                       — read balance
 *   * POST /v1/wallets/{id}/credit               — admin topup
 *   * POST /v1/wallets/{id}/debit                — admin debit
 *   * POST /v1/wallets/{id}/freeze               — admin freeze
 *   * POST /v1/wallets/{id}/unfreeze             — admin unfreeze
 *
 * Note: most wallet writes happen via the Kafka consumers
 * (customer.wallet.topup.requested.v1, etc.) — these REST endpoints
 * exist for admin tooling and customer-service self-service topups.
 */
@RestController
@RequestMapping("/v1/wallets")
class WalletController(
    private val walletService: WalletService,
) {

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    fun get(@PathVariable("id") id: String): WalletResponse {
        val walletId = UUID.fromString(id)
        val entries = walletService.entries(walletId)
        val balance = walletService.balance(walletId)
            ?: throw java.util.NoSuchElementException("wallet $walletId not found")
        // We don't carry the wallet aggregate directly through the
        // service for read paths; the response is constructed from the
        // balance + the most-recent entry's wallet_id metadata. A future
        // graduate extends WalletService with a `findById` overload that
        // returns the full Wallet aggregate.
        return WalletResponse(
            walletId = walletId.toString(),
            customerId = entries.firstOrNull()?.walletId?.toString() ?: walletId.toString(),
            walletKind = Wallet.KIND_CUSTOMER,
            currency = entries.firstOrNull()?.currency ?: "USD",
            state = Wallet.STATE_ACTIVE,
            balanceMinor = balance,
            heldBalanceMinor = 0L,
        )
    }

    @PostMapping("/{id}/credit")
    @PreAuthorize("hasAuthority('SCOPE_wallet.write') or hasAuthority('SCOPE_payment.admin')")
    fun credit(
        @PathVariable("id") id: String,
        @Valid @RequestBody req: CreditRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): ResponseEntity<Map<String, Any?>> {
        val walletId = UUID.fromString(id)
        val actingUserId = UUID.fromString(actingUser)
        val eventId = UUID.randomUUID()
        val correlationId = UUID.randomUUID()
        val entry = walletService.credit(
            walletId = walletId,
            amountMinor = req.amountMinor,
            eventId = eventId,
            source = req.source,
            sourceId = req.sourceId?.let(UUID::fromString),
            description = req.description,
            correlationId = correlationId,
            createdBy = actingUserId,
        )
        return ResponseEntity.ok(mapOf(
            "wallet_id" to walletId.toString(),
            "entry_id" to entry.id.toString(),
            "balance_after_minor" to entry.balanceAfterMinor,
        ))
    }

    @PostMapping("/{id}/debit")
    @PreAuthorize("hasAuthority('SCOPE_wallet.write') or hasAuthority('SCOPE_payment.admin')")
    fun debit(
        @PathVariable("id") id: String,
        @Valid @RequestBody req: DebitRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): ResponseEntity<Map<String, Any?>> {
        val walletId = UUID.fromString(id)
        val actingUserId = UUID.fromString(actingUser)
        val eventId = UUID.randomUUID()
        val correlationId = UUID.randomUUID()
        val entry = walletService.debit(
            walletId = walletId,
            amountMinor = req.amountMinor,
            eventId = eventId,
            source = req.source,
            sourceId = req.sourceId?.let(UUID::fromString),
            description = req.description,
            correlationId = correlationId,
            createdBy = actingUserId,
        )
        return ResponseEntity.ok(mapOf(
            "wallet_id" to walletId.toString(),
            "entry_id" to entry.id.toString(),
            "balance_after_minor" to entry.balanceAfterMinor,
        ))
    }

    @PostMapping("/{id}/freeze")
    @PreAuthorize("hasAuthority('SCOPE_payment.admin')")
    fun freeze(@PathVariable("id") id: String, @RequestHeader("X-User-Id") actingUser: String): ResponseEntity<Void> {
        walletService.freeze(UUID.fromString(id), UUID.fromString(actingUser))
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{id}/unfreeze")
    @PreAuthorize("hasAuthority('SCOPE_payment.admin')")
    fun unfreeze(@PathVariable("id") id: String, @RequestHeader("X-User-Id") actingUser: String): ResponseEntity<Void> {
        walletService.unfreeze(UUID.fromString(id), UUID.fromString(actingUser))
        return ResponseEntity.noContent().build()
    }
}

data class CreditRequest(
    @field:Min(1) val amountMinor: Long,
    @field:NotBlank @field:Pattern(regexp = "^(payment_capture|refund|reward_grant|wallet_topup|manual_adjustment)$")
    val source: String,
    val sourceId: String? = null,
    val description: String? = null,
)

data class DebitRequest(
    @field:Min(1) val amountMinor: Long,
    @field:NotBlank @field:Pattern(regexp = "^(wallet_transfer|merchant_payout|driver_payout|courier_payout|manual_adjustment)$")
    val source: String,
    val sourceId: String? = null,
    val description: String? = null,
)