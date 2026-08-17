package com.trips_enjoy.payment.api

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.util.UUID

/**
 * DTOs for the payment-intent REST surface. Mirrors
 * docs/services/payment-service/INTEGRATION.md §1 exactly.
 *
 * Validation rules:
 *   * amount_minor > 0  (validated via @Min)
 *   * currency is ISO-4217 3-letter code  (validated via @Pattern)
 *   * capture_mode ∈ {manual, auto}
 *   * service ∈ {trip, food_order, courier_delivery, wallet_topup, manual}
 *   * Idempotency-Key header is required for every mutating route
 */

data class CreatePaymentIntentRequest(
    @field:NotBlank val customerId: String,
    @field:NotBlank val requestId: String,
    @field:NotBlank @field:Pattern(regexp = "^(trip|food_order|courier_delivery|wallet_topup|manual)$")
    val service: String,
    @field:Min(1) val amountMinor: Long,
    @field:NotBlank @field:Pattern(regexp = "^[A-Z]{3}$") val currency: String,
    @field:NotBlank val gatewayRegion: String,
    @field:Pattern(regexp = "^(manual|auto)$") val captureMode: String = "manual",
    val gatewayPin: String? = null,
    val tenantId: String? = null,
    val method: String = "card",
    val cityId: String? = null,
    val description: String? = null,
    val metadata: Map<String, Any?>? = null,
    val correlationId: String? = null,
) {
    fun customerIdAsUuid(): UUID = UUID.fromString(customerId)
    fun requestIdAsUuid(): UUID = UUID.fromString(requestId)
    fun cityIdAsUuidOrNull(): UUID? = cityId?.let(UUID::fromString)
}

data class CreatePaymentIntentResponse(
    val paymentIntentId: String,
    val state: String,
    val amountMinor: Long,
    val currency: String,
    val gatewayId: String,
    val gatewayIntentId: String?,
    val correlationId: String,
)

data class AuthorizePaymentIntentRequest(
    @field:NotBlank @field:Size(min = 1, max = 4096) val gatewayToken: String,
)

data class AuthorizePaymentIntentResponse(
    val paymentIntentId: String,
    val state: String,
    val amountMinor: Long,
    val currency: String,
    val authorizedAt: String,
)

data class CapturePaymentIntentRequest(
    val amountMinor: Long? = null,
)

data class CapturePaymentIntentResponse(
    val paymentIntentId: String,
    val state: String,
    val capturedMinor: Long,
    val currency: String,
    val capturedAt: String,
)

data class VoidPaymentIntentRequest(
    val reason: String? = null,
)

data class RefundPaymentIntentRequest(
    @field:Min(1) val refundAmountMinor: Long,
    val reason: String? = null,
)

data class WalletResponse(
    val walletId: String,
    val customerId: String,
    val walletKind: String,
    val currency: String,
    val state: String,
    val balanceMinor: Long,
    val heldBalanceMinor: Long,
)

data class DriverEarningsResponse(
    val earningsId: String,
    val driverId: String,
    val periodKind: String,
    val periodStart: String,
    val periodEnd: String,
    val currency: String,
    val ridesCount: Int,
    val grossFareMinor: Long,
    val commissionMinor: Long,
    val tipMinor: Long,
    val bonusMinor: Long,
    val guaranteedTopupMinor: Long,
    val correctionMinor: Long,
    val netPayMinor: Long,
    val state: String,
)

data class MerchantSettlementResponse(
    val settlementId: String,
    val merchantId: String,
    val periodStart: String,
    val periodEnd: String,
    val currency: String,
    val ordersCount: Int,
    val grossRevenueMinor: Long,
    val commissionMinor: Long,
    val adjustmentsMinor: Long,
    val refundReversalMinor: Long,
    val netPayoutMinor: Long,
    val state: String,
)