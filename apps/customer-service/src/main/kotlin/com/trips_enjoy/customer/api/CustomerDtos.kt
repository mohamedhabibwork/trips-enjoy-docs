package com.trips_enjoy.customer.api

import com.trips_enjoy.customer.domain.Customer
import com.trips_enjoy.customer.domain.CustomerKycHistory
import com.trips_enjoy.customer.domain.CustomerLtvHistory
import com.trips_enjoy.customer.domain.CustomerSegmentHistory
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

// ---------------------------------------------------------------------------
// Read endpoints (INTEGRATION.md §1.1 / §1.4)
// ---------------------------------------------------------------------------

data class CustomerResponse(
    val id: UUID,
    val identity_id: UUID,
    val name: String?,
    val email: String?,
    val phone: String?,
    val kyc_tier: String,
    val kyc_verification_id: UUID?,
    val kyc_verified_at: Instant?,
    val default_payment_method_id: UUID?,
    val default_address_id: UUID?,
    val primary_city_id: UUID?,
    val ltv_minor: Long,
    val ltv_currency: String,
    val ltv_updated_at: Instant?,
    val segment: String,
    val rides_this_month: Int,
    val last_active_at: Instant?,
    val status: String,
    val created_at: Instant,
    val updated_at: Instant,
)

fun Customer.toResponse(): CustomerResponse =
    CustomerResponse(
        id = requireNotNull(id),
        identity_id = identityId,
        name = name,
        email = email,
        phone = phone,
        kyc_tier = kycTier,
        kyc_verification_id = kycVerificationId,
        kyc_verified_at = kycVerifiedAt,
        default_payment_method_id = defaultPaymentMethodId,
        default_address_id = defaultAddressId,
        primary_city_id = primaryCityId,
        ltv_minor = ltvMinor,
        ltv_currency = ltvCurrency,
        ltv_updated_at = ltvUpdatedAt,
        segment = segment,
        rides_this_month = ridesThisMonth,
        last_active_at = lastActiveAt,
        status = status,
        created_at = createdAt ?: java.time.Instant.EPOCH,
        updated_at = updatedAt ?: java.time.Instant.EPOCH,
    )

data class KycLimits(
    val tier_0: Long?,
    val tier_1: Long?,
    val tier_2: Long?,
    val tier_3: Long?,
)

data class KycResponse(
    val tier: String,
    val verification_id: UUID?,
    val verified_at: Instant?,
    val limits: KycLimits,
)

data class KycHistoryResponse(
    val items: List<KycHistoryItem>,
)

data class KycHistoryItem(
    val from_tier: String?,
    val to_tier: String,
    val verification_id: UUID?,
    val actor: UUID?,
    val reason: String?,
    val occurred_at: Instant,
)

fun CustomerKycHistory.toItem(): KycHistoryItem =
    KycHistoryItem(
        from_tier = fromTier,
        to_tier = toTier,
        verification_id = verificationId,
        actor = actor,
        reason = reason,
        occurred_at = occurredAt,
    )

data class SegmentHistoryResponse(
    val items: List<SegmentHistoryItem>,
)

data class SegmentHistoryItem(
    val from_segment: String?,
    val to_segment: String,
    val trigger: String,
    val occurred_at: Instant,
)

fun CustomerSegmentHistory.toItem(): SegmentHistoryItem =
    SegmentHistoryItem(
        from_segment = fromSegment,
        to_segment = toSegment,
        trigger = trigger,
        occurred_at = occurredAt,
    )

data class LtvHistoryResponse(
    val items: List<LtvHistoryItem>,
)

data class LtvHistoryItem(
    val delta_minor: Long,
    val currency: String,
    val service: String,
    val request_id: UUID?,
    val occurred_at: Instant,
)

fun CustomerLtvHistory.toItem(): LtvHistoryItem =
    LtvHistoryItem(
        delta_minor = deltaMinor,
        currency = currency,
        service = service,
        request_id = requestId,
        occurred_at = pk.occurredAt,
    )

// ---------------------------------------------------------------------------
// Write endpoints (INTEGRATION.md §1.2 / §1.3 / §1.5 / §1.6 / §1.9)
// ---------------------------------------------------------------------------

data class CreateCustomerRequest(
    val identity_id: UUID,
    val name: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val primary_city_id: UUID? = null,
)

data class UpdateCustomerRequest(
    val name: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val primary_city_id: UUID? = null,
    val expected_row_version: Long? = null,
)

data class KycUpgradeRequest(
    val document_file_ids: List<UUID>,
    @field:NotBlank @field:Pattern(regexp = "^tier_[0-3]$")
    val target_tier: String,
)

data class SuspendRequest(
    @field:NotBlank @field:Size(min = 1, max = 64) val reason: String,
    val note: String? = null,
)

data class ReinstateRequest(
    val note: String? = null,
)

data class DisableRequest(
    @field:NotBlank @field:Size(min = 1, max = 64) val reason: String,
    val note: String? = null,
)

data class EraseRequest(
    val legal_basis: String? = null,
    val note: String? = null,
)

data class SetDefaultMethodResponse(
    val customer: CustomerResponse,
    val correlation_id: UUID,
)

data class SetDefaultAddressResponse(
    val customer: CustomerResponse,
    val correlation_id: UUID,
)

data class EraseResponse(
    val customer: CustomerResponse,
    val warnings: List<String>,
    val correlation_id: UUID,
)

// ---------------------------------------------------------------------------
// Loyalty account (Appendix A.4)
// ---------------------------------------------------------------------------

data class LoyaltyAccountResponse(
    val customer_id: UUID,
    val balance: Long,
    val currency: String,
    val tier: String,
    val updated_at: Instant,
)

data class LoyaltyHistoryEntryResponse(
    val entry_id: UUID,
    val delta: Long,
    val kind: String,
    val occurred_at: Instant,
)
