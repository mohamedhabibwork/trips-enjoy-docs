package com.trips_enjoy.customer.application

import com.trips_enjoy.customer.api.ApiException
import com.trips_enjoy.customer.domain.CustomerRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Loyalty account exposure (INTEGRATION.md Appendix A.4 / README §A.4).
 *
 * The loyalty rules (earn/burn math, tier thresholds, eligibility,
 * promo-binding) live in `pricing-service`. This service holds the
 * per-user loyalty account (balance, tier, history) and exposes it
 * to the customer-facing API.
 *
 * For the v1 scaffold the loyalty account is a thin mirror of the
 * customer aggregate (balance = 0, tier = "standard"). The
 * `consumeLoyaltyTierChanged` handler is the integration point where
 * the projected loyalty tier is refreshed.
 */
@Service
class LoyaltyAccountService(
    private val customerRepository: CustomerRepository,
) {
    data class LoyaltyAccount(
        val customerId: UUID,
        val balance: Long,
        val currency: String,
        val tier: String,
        val updatedAt: Instant,
    )

    data class LoyaltyHistoryEntry(
        val entryId: UUID,
        val delta: Long,
        val kind: String,
        val occurredAt: Instant,
    )

    @Transactional(readOnly = true)
    fun getAccount(customerId: UUID): LoyaltyAccount {
        val customer =
            customerRepository.findById(customerId).orElseThrow {
                ApiException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Customer $customerId not found")
            }
        return LoyaltyAccount(
            customerId = requireNotNull(customer.id),
            balance = 0L,
            currency = customer.ltvCurrency,
            tier = customer.segment,
            updatedAt = customer.updatedAt ?: Instant.now(),
        )
    }

    @Transactional(readOnly = true)
    fun getHistory(customerId: UUID, limit: Int = 50): List<LoyaltyHistoryEntry> {
        // Phase 1: no internal loyalty history table; the consumer of
        // pricing-service loyalty events projects the per-customer list.
        // We return an empty list (validated against the customer row) so
        // the read endpoint is correct under the v1 contract.
        customerRepository.findById(customerId).orElseThrow {
            ApiException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Customer $customerId not found")
        }
        return emptyList()
    }

    /**
     * Consumes `loyalty.tier.changed.v1` from `pricing-service`.
     * Maps the pricing tier to the canonical customer segment and
     * updates the customer row so the loyalty account endpoint shows
     * the right tier.
     */
    @Transactional
    fun applyTierChanged(
        customerId: UUID,
        newTier: String,
        correlationId: UUID,
    ) {
        val customer =
            customerRepository.findById(customerId).orElseThrow {
                ApiException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Customer $customerId not found")
            }
        // The pricing tier names differ from the platform segment names
        // (e.g. `gold` vs `vip`). The mapping is intentionally
        // conservative: only the known loyalty tiers are mapped.
        val mappedSegment = when (newTier.lowercase()) {
            "bronze" -> "standard"
            "silver" -> "frequent"
            "gold", "platinum" -> "vip"
            else -> customer.segment
        }
        if (mappedSegment != customer.segment) {
            customer.segment = mappedSegment
            customer.segmentUpdatedAt = Instant.now()
            customerRepository.save(customer)
        }
    }
}
