package com.trips_enjoy.search.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface ReindexJobRepository : JpaRepository<ReindexJob, UUID> {
    fun findByTenantIdAndVerticalOrderByCreatedAtDesc(tenantId: String, vertical: String): List<ReindexJob>
    fun findByStateInOrderByCreatedAtAsc(states: List<String>): List<ReindexJob>
}

@Repository
interface QueryLogRepository : JpaRepository<QueryLog, UUID> {
    fun findByTenantIdAndVerticalAndOccurredAtAfterOrderByOccurredAtDesc(
        tenantId: String, vertical: String, after: Instant,
    ): List<QueryLog>
}

@Repository
interface RelevanceConfigRepository : JpaRepository<RelevanceConfig, UUID> {
    fun findByTenantIdAndVertical(tenantId: String, vertical: String): List<RelevanceConfig>
    fun findByTenantIdAndVerticalAndField(tenantId: String, vertical: String, field: String): RelevanceConfig?
}

@Repository
interface IndexHealthRepository : JpaRepository<IndexHealth, UUID> {
    fun findFirstByClusterNameOrderByRecordedAtDesc(clusterName: String): IndexHealth?
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