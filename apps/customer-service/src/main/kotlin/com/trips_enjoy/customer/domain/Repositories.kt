package com.trips_enjoy.customer.domain

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * Customer aggregate root. The `lockById` variants provide the
 * `SELECT ... FOR UPDATE` row-level lock that the LTV-update,
 * suspension, and erase paths need to serialize concurrent writes
 * (SRS §14).
 */
@Repository
interface CustomerRepository : JpaRepository<Customer, UUID> {
    fun findByIdentityIdAndDeletedAtIsNull(identityId: UUID): Optional<Customer>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Customer c where c.id = :id and c.deletedAt is null")
    fun lockById(@Param("id") id: UUID): Optional<Customer>

    @Query(
        """
        select c from Customer c
         where c.status = 'active' and c.deletedAt is null
        """,
    )
    fun findAllActive(): List<Customer>
}

@Repository
interface CustomerKycHistoryRepository : JpaRepository<CustomerKycHistory, UUID> {
    fun findAllByCustomerIdOrderByOccurredAtDesc(customerId: UUID): List<CustomerKycHistory>
}

@Repository
interface CustomerLtvHistoryRepository : JpaRepository<CustomerLtvHistory, CustomerLtvHistoryPk> {
    @Query("select coalesce(sum(h.deltaMinor), 0) from CustomerLtvHistory h where h.customerId = :customerId")
    fun sumDeltaByCustomerId(@Param("customerId") customerId: UUID): Long
}

@Repository
interface CustomerSegmentHistoryRepository : JpaRepository<CustomerSegmentHistory, UUID> {
    fun findAllByCustomerIdOrderByOccurredAtDesc(customerId: UUID): List<CustomerSegmentHistory>
}

@Repository
interface CustomerAuditLogRepository : JpaRepository<CustomerAuditLog, UUID> {
    fun findAllByCustomerIdOrderByOccurredAtDesc(customerId: UUID): List<CustomerAuditLog>
}

@Repository
interface OutboxRepository : JpaRepository<OutboxEvent, UUID> {
    @Query("select o from OutboxEvent o where o.publishedAt is null order by o.createdAt asc")
    fun findTop100ByPublishedAtIsNullOrderByCreatedAtAsc(
        pageable: org.springframework.data.domain.Pageable,
    ): List<OutboxEvent>
}

@Repository
interface InboxRepository : JpaRepository<InboxEvent, UUID> {
    fun existsByEventId(eventId: UUID): Boolean

    @Modifying
    @Query("delete from InboxEvent i where i.receivedAt < :cutoff")
    fun deleteAllByReceivedAtBefore(@Param("cutoff") cutoff: Instant): Long
}

@Repository
interface IdempotencyRepository : JpaRepository<Idempotency, UUID> {
    @Modifying
    @Query("delete from Idempotency i where i.expiresAt < :cutoff")
    fun deleteAllByExpiresAtBefore(@Param("cutoff") cutoff: Instant): Long
}
