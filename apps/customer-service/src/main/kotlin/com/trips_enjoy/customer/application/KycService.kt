package com.trips_enjoy.customer.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.customer.api.ApiException
import com.trips_enjoy.customer.domain.Customer
import com.trips_enjoy.customer.domain.CustomerAuditLog
import com.trips_enjoy.customer.domain.CustomerAuditLogRepository
import com.trips_enjoy.customer.domain.CustomerKycHistory
import com.trips_enjoy.customer.domain.CustomerKycHistoryRepository
import com.trips_enjoy.customer.domain.CustomerRepository
import com.trips_enjoy.customer.util.uuidV7
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * KYC tier upgrade path (INTEGRATION.md §1.5 / §3.8 / WORKFLOWS.md §2).
 *
 * The service is the writer of the `customers.kyc_tier` column. The
 * KYC provider is the source of truth for the verified tier; this
 * service submits the documents, stores the resulting `verification_id`,
 * and emits `customer.kyc.tier_changed.v1` + `customer.updated.v1`.
 *
 * Phase 1 (this implementation) is the synchronous provider path:
 *   - the provider returns the verified tier in the same call (small
 *     markets) OR
 *   - the operator pre-empts the provider via an admin override with
 *     a reason (no `verification_id`).
 *
 * The async-path webhook handler (provider callback) is intentionally
 * stubbed (see `KycProviderWebhookStub`) so the code path is present
 * and unit-testable without a real provider.
 */
@Service
class KycService(
    private val customerRepository: CustomerRepository,
    private val kycHistoryRepository: CustomerKycHistoryRepository,
    private val auditLogRepository: CustomerAuditLogRepository,
    private val readService: CustomerReadService,
    private val eventPublisher: EventPublisher,
    private val mapper: ObjectMapper,
    private val kycProvider: KycProviderStub,
) {
    /**
     * POST /v1/customers/{id}/kyc/upgrade.
     *
     * Submits the documents to the KYC provider and updates the tier
     * if (and only if) the provider returns a higher tier.
     */
    @Transactional
    fun upgrade(
        customerId: UUID,
        documentFileIds: List<UUID>,
        targetTier: String,
        actorId: UUID,
        actorType: String,
        correlationId: UUID,
    ): Customer {
        validateTier(targetTier)
        if (documentFileIds.isEmpty()) {
            throw ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "KYC_DOCUMENTS_REQUIRED",
                "At least one document_file_id is required",
            )
        }
        val customer =
            customerRepository.lockById(customerId).orElseThrow {
                ApiException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Customer $customerId not found")
            }
        if (customer.status == "erased") {
            throw ApiException(HttpStatus.CONFLICT, "CUSTOMER_ERASED", "Customer $customerId has been erased")
        }
        if (tierRank(targetTier) <= tierRank(customer.kycTier)) {
            throw ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "KYC_TIER_NOT_UPGRADE",
                "Target tier $targetTier is not greater than current tier ${customer.kycTier}",
            )
        }
        val verification =
            runCatching {
                kycProvider.submitVerification(
                    customerId = customerId,
                    documentFileIds = documentFileIds,
                    targetTier = targetTier,
                )
            }.getOrElse { failure ->
                throw ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "DEPENDENCY_UPSTREAM_FAILURE",
                    "KYC provider failed: ${failure.message}",
                )
            }
        val before = snapshot(customer)
        val fromTier = customer.kycTier
        customer.kycTier = verification.verifiedTier
        customer.kycVerificationId = verification.verificationId
        customer.kycVerifiedAt = Instant.now()
        customer.kycDocumentFileIds = documentFileIds.toTypedArray()
        customerRepository.save(customer)
        val customerIdValue = requireNotNull(customer.id) { "Customer.id must be assigned after save" }
        kycHistoryRepository.save(
            CustomerKycHistory(
                id = uuidV7(),
                customerId = customerId,
                fromTier = fromTier,
                toTier = customer.kycTier,
                verificationId = verification.verificationId,
                actor = actorId,
                reason = "upgrade to ${customer.kycTier}",
            ),
        )
        auditLogRepository.save(
            CustomerAuditLog(
                id = uuidV7(),
                customerId = customerId,
                action = "kyc_change",
                actor = actorId,
                actorType = actorType,
                before = mapper.writeValueAsString(before),
                after = mapper.writeValueAsString(snapshot(customer)),
                reason = "tier $fromTier -> ${customer.kycTier}",
                correlationId = correlationId,
            ),
        )
        eventPublisher.publish(
            topic = "customer.kyc.tier_changed",
            eventName = "customer.kyc.tier_changed.v1",
            aggregateType = "Customer",
            aggregateId = customerIdValue,
            data =
                mapOf(
                    "customer_id" to customerIdValue.toString(),
                    "from_tier" to fromTier,
                    "to_tier" to customer.kycTier,
                    "verification_id" to verification.verificationId.toString(),
                    "actor" to actorId.toString(),
                    "occurred_at" to customer.kycVerifiedAt.toString(),
                ),
            correlationId = correlationId,
        )
        eventPublisher.publish(
            topic = "customer.updated",
            eventName = "customer.updated.v1",
            aggregateType = "Customer",
            aggregateId = customerIdValue,
            data =
                mapOf(
                    "customer_id" to customerIdValue.toString(),
                    "changed_fields" to listOf("kyc_tier"),
                    "occurred_at" to customer.updatedAt.toString(),
                ),
            correlationId = correlationId,
        )
        readService.invalidate(customerIdValue)
        return customer
    }

    /**
     * Admin override — set the tier directly with a reason.
     * Used for compliance-driven downgrades or when the provider is
     * unreachable and the operator supplies the verified tier.
     */
    @Transactional
    fun adminOverrideTier(
        customerId: UUID,
        toTier: String,
        reason: String,
        actorId: UUID,
        actorType: String,
        correlationId: UUID,
    ): Customer {
        validateTier(toTier)
        if (reason.isBlank()) {
            throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "reason is required for admin override")
        }
        val customer =
            customerRepository.lockById(customerId).orElseThrow {
                ApiException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Customer $customerId not found")
            }
        if (customer.status == "erased") {
            throw ApiException(HttpStatus.CONFLICT, "CUSTOMER_ERASED", "Customer $customerId has been erased")
        }
        val before = snapshot(customer)
        val fromTier = customer.kycTier
        customer.kycTier = toTier
        customer.kycVerifiedAt = Instant.now()
        customerRepository.save(customer)
        val customerIdValue = requireNotNull(customer.id) { "Customer.id must be assigned after save" }
        kycHistoryRepository.save(
            CustomerKycHistory(
                id = uuidV7(),
                customerId = customerId,
                fromTier = fromTier,
                toTier = toTier,
                verificationId = null,
                actor = actorId,
                reason = "admin_override: $reason",
            ),
        )
        auditLogRepository.save(
            CustomerAuditLog(
                id = uuidV7(),
                customerId = customerId,
                action = "kyc_change",
                actor = actorId,
                actorType = actorType,
                before = mapper.writeValueAsString(before),
                after = mapper.writeValueAsString(snapshot(customer)),
                reason = reason,
                correlationId = correlationId,
            ),
        )
        eventPublisher.publish(
            topic = "customer.kyc.tier_changed",
            eventName = "customer.kyc.tier_changed.v1",
            aggregateType = "Customer",
            aggregateId = customerIdValue,
            data =
                mapOf(
                    "customer_id" to customerIdValue.toString(),
                    "from_tier" to fromTier,
                    "to_tier" to toTier,
                    "verification_id" to null,
                    "actor" to actorId.toString(),
                    "occurred_at" to customer.kycVerifiedAt.toString(),
                ),
            correlationId = correlationId,
        )
        readService.invalidate(customerIdValue)
        return customer
    }

    private fun validateTier(tier: String) {
        if (tier !in listOf("tier_0", "tier_1", "tier_2", "tier_3")) {
            throw ApiException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "kyc_tier must be one of tier_0..tier_3",
            )
        }
    }

    private fun tierRank(tier: String): Int =
        when (tier) {
            "tier_0" -> 0
            "tier_1" -> 1
            "tier_2" -> 2
            "tier_3" -> 3
            else -> -1
        }

    private fun snapshot(customer: Customer): Map<String, Any?> =
        mapOf(
            "id" to customer.id?.toString(),
            "kyc_tier" to customer.kycTier,
            "kyc_verification_id" to customer.kycVerificationId?.toString(),
            "row_version" to customer.version,
        )
}

/**
 * Provider stub — synchronous verification path for the initial
 * scaffold. The real `KycProvider` (e.g. Onfido/Jumio) lands behind
 * the same interface in a follow-up migration; the unit-test seam is
 * preserved.
 */
fun interface KycProviderStub {
    fun submitVerification(
        customerId: UUID,
        documentFileIds: List<UUID>,
        targetTier: String,
    ): KycVerificationResult
}

data class KycVerificationResult(
    val verificationId: UUID,
    val verifiedTier: String,
)
