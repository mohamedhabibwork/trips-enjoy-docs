package com.trips_enjoy.audit.domain

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * Audit event repository. Most queries target the partitioned parent table
 * `audit.events`; PostgreSQL's partition pruning handles the rest.
 */
@Repository
interface AuditEventRepository : JpaRepository<AuditEvent, AuditEvent.Pk> {
    fun findByEventId(eventId: UUID): Optional<AuditEvent>
    fun findFirstByOrderByCreatedAtDescIdDesc(): AuditEvent?

    /**
     * Lock the latest row so a concurrent ingest waits until the hash-chain
     * tip is stable. Implements the serialization point per SRS §14
     * ("The hash chain is serialized at the row level
     * `SELECT ... FOR UPDATE` on the latest row").
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from AuditEvent e order by e.createdAt desc, e.id desc")
    fun lockLatest(): List<AuditEvent>

    @Query(
        """
        select e from AuditEvent e
        where (:topic is null or e.topic = :topic)
          and (:tenantId is null or e.tenantId = :tenantId)
          and (:subjectType is null or e.subjectType = :subjectType)
          and (:subjectId is null or e.subjectId = :subjectId)
          and (:correlationId is null or e.correlationId = :correlationId)
          and (:from is null or e.occurredAt >= :from)
          and (:to is null or e.occurredAt <= :to)
        order by e.occurredAt desc, e.id desc
        """,
    )
    fun search(
        @Param("topic") topic: String?,
        @Param("tenantId") tenantId: String?,
        @Param("subjectType") subjectType: String?,
        @Param("subjectId") subjectId: UUID?,
        @Param("correlationId") correlationId: UUID?,
        @Param("from") from: Instant?,
        @Param("to") to: Instant?,
        pageable: Pageable,
    ): List<AuditEvent>

    /**
     * Verify endpoint — stream rows up to (and including) the target event id.
     *
     * Hibernate 7 rejects tuple comparisons against subqueries, so we expand
     * `(createdAt, id) <= target` into the canonical "earlier-or-same-timestamp
     * AND (later-timestamp-or-equal-id)" form.
     */
    @Query(
        """
        select e from AuditEvent e
        where e.createdAt < :createdAt
           or (e.createdAt = :createdAt and e.id <= :id)
        order by e.createdAt asc, e.id asc
        """,
    )
    fun findUpToIncluding(@Param("id") id: UUID, @Param("createdAt") createdAt: java.time.Instant): List<AuditEvent>

    /** Look up the canonical `(id, created_at)` PK so the caller can stream. */
    fun findCreatedAtByEventId(eventId: UUID): Instant?

    @Query("select count(e) from AuditEvent e where e.retentionClass = :cls and e.litigationHold = false and e.retentionUntil is not null and e.retentionUntil < :cutoff")
    fun countPastRetention(@Param("cls") cls: String, @Param("cutoff") cutoff: Instant): Long
}

@Repository
interface AuditReadLogRepository : JpaRepository<AuditReadLog, AuditReadLog.Pk> {
    fun findAllByActorIdOrderByCreatedAtDesc(actorId: UUID, pageable: Pageable): List<AuditReadLog>

    @org.springframework.data.jpa.repository.Modifying
    @Query("delete from AuditReadLog r where r.createdAt < :cutoff")
    fun deleteAllByCreatedAtBefore(@Param("cutoff") cutoff: Instant): Long
}

@Repository
interface LitigationHoldRepository : JpaRepository<LitigationHold, UUID> {
    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): List<LitigationHold>

    @Query(
        """
        select h from LitigationHold h
        where (h.effectiveTo is null or h.effectiveTo > :now)
          and (:tenantId is null or h.tenantId = :tenantId)
          and (:subjectType is null or h.subjectType = :subjectType)
          and (:subjectId is null or h.subjectId = :subjectId)
          and (:topic is null or h.topic = :topic)
        """,
    )
    fun activeHoldsOverlapping(
        @Param("now") now: Instant,
        @Param("tenantId") tenantId: String?,
        @Param("subjectType") subjectType: String?,
        @Param("subjectId") subjectId: UUID?,
        @Param("topic") topic: String?,
    ): List<LitigationHold>
}

@Repository
interface InboxEventRepository : JpaRepository<InboxEvent, UUID> {
    fun existsByEventId(eventId: UUID): Boolean

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("delete from InboxEvent i where i.receivedAt < :cutoff")
    fun deleteAllByReceivedAtBefore(@Param("cutoff") cutoff: Instant): Long
}

@Repository
interface OutboxEventRepository : JpaRepository<OutboxEvent, UUID> {
    fun findTop100ByPublishedAtIsNullOrderByCreatedAtAsc(): List<OutboxEvent>
    fun findAllByTopicAndAggregateId(topic: String, aggregateId: UUID): List<OutboxEvent>
}
