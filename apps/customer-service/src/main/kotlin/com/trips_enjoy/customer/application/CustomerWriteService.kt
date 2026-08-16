package com.trips_enjoy.customer.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.customer.api.ApiException
import com.trips_enjoy.customer.domain.Customer
import com.trips_enjoy.customer.domain.CustomerAuditLog
import com.trips_enjoy.customer.domain.CustomerAuditLogRepository
import com.trips_enjoy.customer.domain.CustomerRepository
import com.trips_enjoy.customer.util.uuidV7
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Write path for customer-service.
 *
 * Covers:
 *  - POST /v1/customers (idempotent on identity_id)
 *  - PATCH /v1/customers/{id} (profile update)
 *  - PUT /v1/customers/{id}/default-payment-method/{pm_id}
 *  - PUT /v1/customers/{id}/default-address/{address_id}
 *  - POST /v1/customers/{id}/suspend / reinstate / disable / erase
 *
 * All writes are atomic across `customers`, `customer_audit_log`, and
 * `outbox` (SRS §14). KYC tier changes, LTV updates, and segment
 * recomputation have their own dedicated services so this one stays
 * focused on profile + state-machine operations.
 *
 * Concurrency: `SELECT ... FOR UPDATE` (lockById) on the customer row
 * for every state-changing operation so two concurrent writes result in
 * one win and one 409 CONFLICT.
 */
@Service
class CustomerWriteService(
    private val customerRepository: CustomerRepository,
    private val auditLogRepository: CustomerAuditLogRepository,
    private val readService: CustomerReadService,
    private val eventPublisher: EventPublisher,
    private val mapper: ObjectMapper,
) {
    /**
     * POST /v1/customers — idempotent on `identity_id`.
     *
     * Creates a new customer at tier_0 with empty defaults. The
     * `identity.user.created.v1` consumer in
     * `integration/events/IdentityUserCreatedConsumer.kt` is the
     * canonical back-channel; this method is the manual entry point.
     */
    @Transactional
    fun create(
        identityId: UUID,
        name: String?,
        email: String?,
        phone: String?,
        primaryCityId: UUID?,
        actorId: UUID,
        correlationId: UUID,
    ): Customer {
        readService.getByIdentityId(identityId)?.let {
            throw ApiException(
                HttpStatus.CONFLICT,
                "CUSTOMER_EXISTS",
                "Customer for identity $identityId already exists",
            )
        }
        val now = Instant.now()
        val customer =
            Customer(
                id = uuidV7(),
                identityId = identityId,
                name = name,
                email = email,
                phone = phone,
                primaryCityId = primaryCityId,
                createdAt = now,
                updatedAt = now,
                createdBy = actorId,
                updatedBy = actorId,
            )
        customerRepository.save(customer)
        recordAudit(
            customerId = customer.id,
            action = "create",
            actorId = actorId,
            actorType = "service",
            before = null,
            after = snapshot(customer),
            reason = "customer created",
            correlationId = correlationId,
        )
        eventPublisher.publish(
            topic = "customer.created",
            eventName = "customer.created.v1",
            aggregateType = "Customer",
            aggregateId = customer.id,
            data =
                mapOf(
                    "customer_id" to customer.id.toString(),
                    "identity_id" to customer.identityId.toString(),
                    "kyc_tier" to customer.kycTier,
                    "primary_city_id" to customer.primaryCityId?.toString(),
                    "occurred_at" to now.toString(),
                ),
            correlationId = correlationId,
        )
        readService.invalidate(customer.id)
        return customer
    }

    /**
     * PATCH /v1/customers/{id} — update profile fields.
     *
     * Accepts `name`, `email`, `phone`, `primary_city_id`. The
     * `expected_row_version` field is the optimistic-lock counter; a
     * mismatched value returns 409 CONFLICT.
     */
    @Transactional
    fun updateProfile(
        customerId: UUID,
        name: String?,
        email: String?,
        phone: String?,
        primaryCityId: UUID?,
        expectedRowVersion: Long?,
        actorId: UUID,
        actorType: String,
        reason: String?,
        correlationId: UUID,
    ): Customer {
        val customer =
            customerRepository.lockById(customerId).orElseThrow {
                ApiException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Customer $customerId not found")
            }
        if (customer.status == "erased") {
            throw ApiException(HttpStatus.CONFLICT, "CUSTOMER_ERASED", "Customer $customerId has been erased")
        }
        if (expectedRowVersion != null && customer.rowVersion != expectedRowVersion) {
            throw ApiException(
                HttpStatus.CONFLICT,
                "VERSION_CONFLICT",
                "Expected row_version $expectedRowVersion but found ${customer.rowVersion}",
            )
        }
        val before = snapshot(customer)
        if (name != null) customer.name = name
        if (email != null) customer.email = email
        if (phone != null) customer.phone = phone
        if (primaryCityId != null) customer.primaryCityId = primaryCityId
        customer.rowVersion = customer.rowVersion + 1
        customer.updatedAt = Instant.now()
        customer.updatedBy = actorId
        customerRepository.save(customer)
        recordAudit(
            customerId = customer.id,
            action = "update",
            actorId = actorId,
            actorType = actorType,
            before = before,
            after = snapshot(customer),
            reason = reason,
            correlationId = correlationId,
        )
        eventPublisher.publish(
            topic = "customer.updated",
            eventName = "customer.updated.v1",
            aggregateType = "Customer",
            aggregateId = customer.id,
            data =
                mapOf(
                    "customer_id" to customer.id.toString(),
                    "changed_fields" to listOfNotNull(
                        name?.let { "name" },
                        email?.let { "email" },
                        phone?.let { "phone" },
                        primaryCityId?.let { "primary_city_id" },
                    ),
                    "occurred_at" to customer.updatedAt.toString(),
                ),
            correlationId = correlationId,
        )
        readService.invalidate(customer.id)
        return customer
    }

    /**
     * Back-channel upsert from `identity.user.created.v1` (idempotent on
     * `identity_id`). Pulls the cached claims and creates a tier_0 row
     * if missing.
     */
    @Transactional
    fun upsertFromIdentity(
        identityId: UUID,
        name: String?,
        email: String?,
        phone: String?,
        primaryCityId: UUID?,
        actorId: UUID,
        correlationId: UUID,
    ): Customer {
        val existing = readService.getByIdentityId(identityId)
        if (existing != null) {
            // Idempotent update of cached claims (only when they differ).
            val changed =
                (name != null && name != existing.name) ||
                    (email != null && email != existing.email) ||
                    (phone != null && phone != existing.phone) ||
                    (primaryCityId != null && primaryCityId != existing.primaryCityId)
            if (!changed) return existing
            return updateProfile(
                customerId = existing.id,
                name = name,
                email = email,
                phone = phone,
                primaryCityId = primaryCityId,
                expectedRowVersion = null,
                actorId = actorId,
                actorType = "service",
                reason = "identity.user.created/v1 back-channel",
                correlationId = correlationId,
            )
        }
        return create(
            identityId = identityId,
            name = name,
            email = email,
            phone = phone,
            primaryCityId = primaryCityId,
            actorId = actorId,
            correlationId = correlationId,
        )
    }

    /**
     * PUT /v1/customers/{id}/default-payment-method/{pm_id}
     *
     * Sets the default payment method reference. The reference is the
     * `payment_method_id` UUID owned by `payment-service`; this service
     * does NOT validate ownership in the read path (INTEGRATION.md §1.10).
     */
    @Transactional
    fun setDefaultPaymentMethod(
        customerId: UUID,
        paymentMethodId: UUID,
        actorId: UUID,
        actorType: String,
        correlationId: UUID,
    ): Customer {
        val customer =
            customerRepository.lockById(customerId).orElseThrow {
                ApiException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Customer $customerId not found")
            }
        if (customer.status == "erased") {
            throw ApiException(HttpStatus.CONFLICT, "CUSTOMER_ERASED", "Customer $customerId has been erased")
        }
        val before = snapshot(customer)
        customer.defaultPaymentMethodId = paymentMethodId
        customer.rowVersion = customer.rowVersion + 1
        customer.updatedAt = Instant.now()
        customer.updatedBy = actorId
        customerRepository.save(customer)
        recordAudit(
            customerId = customer.id,
            action = "default_method_change",
            actorId = actorId,
            actorType = actorType,
            before = before,
            after = snapshot(customer),
            reason = "default_payment_method_id=$paymentMethodId",
            correlationId = correlationId,
        )
        eventPublisher.publish(
            topic = "customer.updated",
            eventName = "customer.updated.v1",
            aggregateType = "Customer",
            aggregateId = customer.id,
            data =
                mapOf(
                    "customer_id" to customer.id.toString(),
                    "default_payment_method_id" to paymentMethodId.toString(),
                    "occurred_at" to customer.updatedAt.toString(),
                ),
            correlationId = correlationId,
        )
        readService.invalidate(customer.id)
        return customer
    }

    /**
     * PUT /v1/customers/{id}/default-address/{address_id}
     */
    @Transactional
    fun setDefaultAddress(
        customerId: UUID,
        addressId: UUID,
        actorId: UUID,
        actorType: String,
        correlationId: UUID,
    ): Customer {
        val customer =
            customerRepository.lockById(customerId).orElseThrow {
                ApiException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Customer $customerId not found")
            }
        if (customer.status == "erased") {
            throw ApiException(HttpStatus.CONFLICT, "CUSTOMER_ERASED", "Customer $customerId has been erased")
        }
        val before = snapshot(customer)
        customer.defaultAddressId = addressId
        customer.rowVersion = customer.rowVersion + 1
        customer.updatedAt = Instant.now()
        customer.updatedBy = actorId
        customerRepository.save(customer)
        recordAudit(
            customerId = customer.id,
            action = "default_address_change",
            actorId = actorId,
            actorType = actorType,
            before = before,
            after = snapshot(customer),
            reason = "default_address_id=$addressId",
            correlationId = correlationId,
        )
        eventPublisher.publish(
            topic = "customer.updated",
            eventName = "customer.updated.v1",
            aggregateType = "Customer",
            aggregateId = customer.id,
            data =
                mapOf(
                    "customer_id" to customer.id.toString(),
                    "default_address_id" to addressId.toString(),
                    "occurred_at" to customer.updatedAt.toString(),
                ),
            correlationId = correlationId,
        )
        readService.invalidate(customer.id)
        return customer
    }

    /**
     * POST /v1/customers/{id}/suspend — admin action.
     */
    @Transactional
    fun suspend(
        customerId: UUID,
        reason: String,
        note: String?,
        actorId: UUID,
        actorType: String,
        correlationId: UUID,
    ): Customer {
        val customer =
            customerRepository.lockById(customerId).orElseThrow {
                ApiException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Customer $customerId not found")
            }
        if (customer.status == "erased") {
            throw ApiException(HttpStatus.CONFLICT, "CUSTOMER_ERASED", "Customer $customerId has been erased")
        }
        if (customer.status == "suspended") {
            throw ApiException(HttpStatus.CONFLICT, "CUSTOMER_ALREADY_SUSPENDED", "Customer $customerId is already suspended")
        }
        if (customer.status == "disabled") {
            throw ApiException(HttpStatus.CONFLICT, "CUSTOMER_DISABLED", "Customer $customerId is disabled")
        }
        validateReason(reason)
        val before = snapshot(customer)
        customer.status = "suspended"
        customer.suspendedReason = reason
        customer.suspendedAt = Instant.now()
        customer.suspendedBy = actorId
        customer.rowVersion = customer.rowVersion + 1
        customer.updatedAt = Instant.now()
        customer.updatedBy = actorId
        customerRepository.save(customer)
        recordAudit(
            customerId = customer.id,
            action = "suspend",
            actorId = actorId,
            actorType = actorType,
            before = before,
            after = snapshot(customer),
            reason = note ?: reason,
            correlationId = correlationId,
        )
        eventPublisher.publish(
            topic = "customer.suspended",
            eventName = "customer.suspended.v1",
            aggregateType = "Customer",
            aggregateId = customer.id,
            data =
                mapOf(
                    "customer_id" to customer.id.toString(),
                    "reason" to reason,
                    "suspended_by" to actorId.toString(),
                    "occurred_at" to customer.suspendedAt.toString(),
                ),
            correlationId = correlationId,
        )
        readService.invalidate(customer.id)
        return customer
    }

    /**
     * POST /v1/customers/{id}/reinstate — admin action.
     */
    @Transactional
    fun reinstate(
        customerId: UUID,
        note: String?,
        actorId: UUID,
        actorType: String,
        correlationId: UUID,
    ): Customer {
        val customer =
            customerRepository.lockById(customerId).orElseThrow {
                ApiException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Customer $customerId not found")
            }
        if (customer.status != "suspended") {
            throw ApiException(HttpStatus.CONFLICT, "CUSTOMER_NOT_SUSPENDED", "Customer $customerId is not suspended")
        }
        val before = snapshot(customer)
        customer.status = "active"
        customer.suspendedReason = null
        customer.suspendedAt = null
        customer.suspendedBy = null
        customer.rowVersion = customer.rowVersion + 1
        customer.updatedAt = Instant.now()
        customer.updatedBy = actorId
        customerRepository.save(customer)
        recordAudit(
            customerId = customer.id,
            action = "reinstate",
            actorId = actorId,
            actorType = actorType,
            before = before,
            after = snapshot(customer),
            reason = note,
            correlationId = correlationId,
        )
        eventPublisher.publish(
            topic = "customer.reinstated",
            eventName = "customer.reinstated.v1",
            aggregateType = "Customer",
            aggregateId = customer.id,
            data =
                mapOf(
                    "customer_id" to customer.id.toString(),
                    "reinstated_by" to actorId.toString(),
                    "occurred_at" to customer.updatedAt.toString(),
                ),
            correlationId = correlationId,
        )
        readService.invalidate(customer.id)
        return customer
    }

    /**
     * POST /v1/customers/{id}/disable — permanent (compliance / legal).
     */
    @Transactional
    fun disable(
        customerId: UUID,
        reason: String,
        note: String?,
        actorId: UUID,
        actorType: String,
        correlationId: UUID,
    ): Customer {
        val customer =
            customerRepository.lockById(customerId).orElseThrow {
                ApiException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Customer $customerId not found")
            }
        if (customer.status == "erased") {
            throw ApiException(HttpStatus.CONFLICT, "CUSTOMER_ERASED", "Customer $customerId has been erased")
        }
        validateReason(reason)
        val before = snapshot(customer)
        customer.status = "disabled"
        customer.disabledAt = Instant.now()
        customer.rowVersion = customer.rowVersion + 1
        customer.updatedAt = Instant.now()
        customer.updatedBy = actorId
        customerRepository.save(customer)
        recordAudit(
            customerId = customer.id,
            action = "disable",
            actorId = actorId,
            actorType = actorType,
            before = before,
            after = snapshot(customer),
            reason = note ?: reason,
            correlationId = correlationId,
        )
        eventPublisher.publish(
            topic = "customer.disabled",
            eventName = "customer.disabled.v1",
            aggregateType = "Customer",
            aggregateId = customer.id,
            data =
                mapOf(
                    "customer_id" to customer.id.toString(),
                    "reason" to reason,
                    "disabled_by" to actorId.toString(),
                    "occurred_at" to customer.disabledAt.toString(),
                ),
            correlationId = correlationId,
        )
        readService.invalidate(customer.id)
        return customer
    }

    /**
     * POST /v1/customers/{id}/erase — GDPR right-to-erasure.
     *
     * Anonymizes PII, preserves the customer_id and identity_id for
     * referential integrity, sets status='erased' and deleted_at. The
     * row is a tombstone; downstream services consume
     * `customer.erased.v1` and redact PII from their own profile tables.
     */
    @Transactional
    fun erase(
        customerId: UUID,
        legalBasis: String?,
        note: String?,
        actorId: UUID,
        actorType: String,
        correlationId: UUID,
    ): Customer {
        val customer =
            customerRepository.lockById(customerId).orElseThrow {
                ApiException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Customer $customerId not found")
            }
        if (customer.status == "erased") {
            throw ApiException(HttpStatus.CONFLICT, "CUSTOMER_ERASED", "Customer $customerId has been erased")
        }
        val before = snapshot(customer)
        customer.name = "REDACTED"
        customer.email = "REDACTED"
        customer.phone = "REDACTED"
        customer.kycDocumentFileIds = emptyArray()
        customer.defaultPaymentMethodId = null
        customer.defaultAddressId = null
        customer.status = "erased"
        customer.erasedAt = Instant.now()
        customer.deletedAt = Instant.now()
        customer.rowVersion = customer.rowVersion + 1
        customer.updatedAt = Instant.now()
        customer.updatedBy = actorId
        customerRepository.save(customer)
        recordAudit(
            customerId = customer.id,
            action = "erase",
            actorId = actorId,
            actorType = actorType,
            before = before,
            after = snapshot(customer),
            reason = note ?: "legal_basis=$legalBasis",
            correlationId = correlationId,
        )
        eventPublisher.publish(
            topic = "customer.erased",
            eventName = "customer.erased.v1",
            aggregateType = "Customer",
            aggregateId = customer.id,
            data =
                mapOf(
                    "customer_id" to customer.id.toString(),
                    "identity_id" to customer.identityId.toString(),
                    "legal_basis" to legalBasis,
                    "erased_by" to actorId.toString(),
                    "occurred_at" to customer.erasedAt.toString(),
                ),
            correlationId = correlationId,
        )
        readService.invalidate(customer.id)
        return customer
    }

    private fun recordAudit(
        customerId: UUID,
        action: String,
        actorId: UUID?,
        actorType: String,
        before: Map<String, Any?>?,
        after: Map<String, Any?>?,
        reason: String?,
        correlationId: UUID,
    ) {
        auditLogRepository.save(
            CustomerAuditLog(
                id = uuidV7(),
                customerId = customerId,
                action = action,
                actor = actorId,
                actorType = actorType,
                before = before?.let { mapper.writeValueAsString(it) },
                after = after?.let { mapper.writeValueAsString(it) },
                reason = reason,
                correlationId = correlationId,
            ),
        )
    }

    private fun snapshot(customer: Customer): Map<String, Any?> =
        mapOf(
            "id" to customer.id.toString(),
            "identity_id" to customer.identityId.toString(),
            "kyc_tier" to customer.kycTier,
            "segment" to customer.segment,
            "status" to customer.status,
            "row_version" to customer.rowVersion,
        )

    private fun validateReason(reason: String) {
        if (reason.isBlank()) {
            throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "reason is required")
        }
        // Mirror identity-service's known suspension reasons.
        val allowed = setOf("fraud", "payment_failure", "compliance", "tos_violation", "security", "legal", "admin")
        if (reason !in allowed) {
            throw ApiException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "reason must be one of $allowed",
            )
        }
    }
}
