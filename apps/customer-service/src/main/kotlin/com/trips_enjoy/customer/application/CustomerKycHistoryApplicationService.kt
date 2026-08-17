package com.trips_enjoy.customer.application

import com.trips_enjoy.customer.domain.CustomerKycHistory
import com.trips_enjoy.customer.domain.CustomerKycHistoryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Read-side helper for the KYC tier history endpoint.
 *
 * The KYC history table is append-only at the database level (V3
 * trigger) so this service is read-only.
 */
@Service
class CustomerKycHistoryApplicationService(
    private val kycHistoryRepository: CustomerKycHistoryRepository,
) {
    @Transactional(readOnly = true)
    fun history(customerId: UUID): List<CustomerKycHistory> =
        kycHistoryRepository.findAllByCustomerIdOrderByOccurredAtDesc(customerId)
}
