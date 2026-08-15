package com.trips_enjoy.payment.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import jakarta.persistence.LockModeType
import java.time.Instant
import java.util.UUID

/**
 * Spring Data JPA repositories for the payment-service aggregates.
 * Mirrors the pattern from customer-service's Repositories.kt and
 * ledger-service's repositories. All write paths go through the
 * application service layer (which uses @Transactional); the
 * repositories are thin Spring Data interfaces.
 */

@Repository
interface PaymentIntentRepository : JpaRepository<PaymentIntent, UUID> {
    fun findByGatewayIntentId(gatewayIntentId: String): PaymentIntent?
    fun findByRequestIdAndService(requestId: UUID, service: String): List<PaymentIntent>
}

@Repository
interface PaymentGatewayRepository : JpaRepository<PaymentGateway, String> {
    fun findByState(state: String): List<PaymentGateway>
    fun findByIsDefaultTrue(): PaymentGateway?
    fun findByKind(kind: String): List<PaymentGateway>
    fun findByIdInAndState(ids: Collection<String>, state: String): List<PaymentGateway>
}

@Repository
interface PaymentAttemptRepository : JpaRepository<PaymentAttempt, UUID> {
    fun findByPaymentIntentIdOrderByStartedAtDesc(paymentIntentId: UUID): List<PaymentAttempt>
    fun findByGatewayIdAndStartedAtBetween(gatewayId: String, start: Instant, end: Instant): List<PaymentAttempt>
}

@Repository
interface WalletRepository : JpaRepository<Wallet, UUID> {
    fun findByCustomerIdAndWalletKindAndCurrencyAndDeletedAtIsNull(
        customerId: UUID, walletKind: String, currency: String
    ): Wallet?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wallet w WHERE w.id = :id")
    fun findByIdForUpdate(@Param("id") id: UUID): Wallet?
}

@Repository
interface WalletEntryRepository : JpaRepository<WalletEntry, UUID> {
    fun findByWalletIdOrderByPostedAtDesc(walletId: UUID): List<WalletEntry>

    fun findByEventIdAndSource(eventId: UUID, source: String): WalletEntry?
}

@Repository
interface DriverEarningsRepository : JpaRepository<DriverEarnings, UUID> {
    fun findByDriverIdAndPeriodKindAndPeriodStart(
        driverId: UUID, periodKind: String, periodStart: Instant
    ): DriverEarnings?

    fun findByDriverIdAndState(driverId: UUID, state: String): List<DriverEarnings>
    fun findByState(state: String): List<DriverEarnings>
}

@Repository
interface CourierEarningsRepository : JpaRepository<CourierEarnings, UUID> {
    fun findByCourierIdAndPeriodKindAndPeriodStart(
        courierId: UUID, periodKind: String, periodStart: Instant
    ): CourierEarnings?

    fun findByCourierIdAndState(courierId: UUID, state: String): List<CourierEarnings>
    fun findByState(state: String): List<CourierEarnings>
}

@Repository
interface MerchantSettlementRepository : JpaRepository<MerchantSettlement, UUID> {
    fun findByMerchantIdAndPeriodStart(merchantId: UUID, periodStart: Instant): MerchantSettlement?
    fun findByMerchantIdAndState(merchantId: UUID, state: String): List<MerchantSettlement>
    fun findByState(state: String): List<MerchantSettlement>
}

@Repository
interface IdempotencyKeyRepository : JpaRepository<IdempotencyKey, UUID> {
    fun findByScopeAndIdemKey(scope: String, idemKey: String): IdempotencyKey?
}

@Repository
interface OutboxEventRepository : JpaRepository<OutboxEvent, UUID> {
    @Query("SELECT o FROM OutboxEvent o WHERE o.publishedAt IS NULL AND o.nextAttemptAt <= :now ORDER BY o.nextAttemptAt ASC")
    fun findPending(@Param("now") now: Instant, pageable: org.springframework.data.domain.Pageable): List<OutboxEvent>

    @Modifying
    @Query("DELETE FROM OutboxEvent o WHERE o.publishedAt IS NOT NULL AND o.publishedAt < :cutoff")
    fun deletePublishedBefore(@Param("cutoff") cutoff: Instant): Int
}

@Repository
interface InboxEventRepository : JpaRepository<InboxEvent, UUID> {
    fun findBySourceTopicAndSourceEventId(sourceTopic: String, sourceEventId: UUID): InboxEvent?

    @Modifying
    @Query("DELETE FROM InboxEvent i WHERE i.consumedAt < :cutoff")
    fun deleteConsumedBefore(@Param("cutoff") cutoff: Instant): Int
}

@Repository
interface GatewayOverrideRepository : JpaRepository<GatewayOverride, UUID> {
    fun findByScopeAndScopeKeyAndEnabledTrueAndDeletedAtIsNull(
        scope: String, scopeKey: String
    ): List<GatewayOverride>

    fun findByScopeAndEnabledTrueAndDeletedAtIsNull(scope: String): List<GatewayOverride>
}

@Repository
interface PaymentGatewayAssignmentRepository : JpaRepository<PaymentGatewayAssignment, UUID> {
    fun findByPaymentIntentIdOrderByEffectiveAtDesc(paymentIntentId: UUID): List<PaymentGatewayAssignment>
}