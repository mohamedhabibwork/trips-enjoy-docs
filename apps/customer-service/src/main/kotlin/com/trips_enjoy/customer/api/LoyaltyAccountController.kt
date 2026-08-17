package com.trips_enjoy.customer.api

import com.trips_enjoy.customer.application.LoyaltyAccountService
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Loyalty account exposure (INTEGRATION.md Appendix A.4 / README §A.5).
 * The actual earn/burn logic lives in `pricing-service`; this service
 * holds the per-customer projected account.
 */
@RestController
@RequestMapping("/v1/customers")
class LoyaltyAccountController(
    private val loyaltyAccountService: LoyaltyAccountService,
) {
    @GetMapping("/{customer_id}/loyalty-account")
    @PreAuthorize(
        "hasAnyAuthority('ROLE_customer.read', 'ROLE_customer.admin', " +
            "'ROLE_customer.read.any', 'ROLE_platform.admin', 'ROLE_platform.super_admin')",
    )
    fun get(
        @PathVariable("customer_id") customerId: UUID,
    ): LoyaltyAccountResponse {
        val account = loyaltyAccountService.getAccount(customerId)
        return LoyaltyAccountResponse(
            customer_id = account.customerId,
            balance = account.balance,
            currency = account.currency,
            tier = account.tier,
            updated_at = account.updatedAt,
        )
    }

    @GetMapping("/{customer_id}/loyalty-account/history")
    @PreAuthorize(
        "hasAnyAuthority('ROLE_customer.read', 'ROLE_customer.admin', " +
            "'ROLE_customer.read.any', 'ROLE_platform.admin', 'ROLE_platform.super_admin')",
    )
    fun history(
        @PathVariable("customer_id") customerId: UUID,
    ): List<LoyaltyHistoryEntryResponse> {
        val entries = loyaltyAccountService.getHistory(customerId)
        return entries.map {
            LoyaltyHistoryEntryResponse(
                entry_id = it.entryId,
                delta = it.delta,
                kind = it.kind,
                occurred_at = it.occurredAt,
            )
        }
    }

    // Suppress unused warning for HttpStatus — kept for future 4xx returns.
    @Suppress("unused")
    private val _placeholder: HttpStatus = HttpStatus.OK
}
