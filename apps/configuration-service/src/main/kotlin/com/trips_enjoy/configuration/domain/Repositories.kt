package com.trips_enjoy.configuration.domain

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.Optional
import java.util.UUID

@Repository
interface DocumentRepository : JpaRepository<Document, UUID> {
    fun findByKey(key: String): Optional<Document>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Document d where d.key = :key")
    fun lockByKey(
        @Param("key") key: String,
    ): Optional<Document>
}

@Repository
interface ConfigurationVersionRepository : JpaRepository<ConfigurationVersion, ConfigurationVersionPk> {
    @Query(
        """
        select v from ConfigurationVersion v
        where v.documentId = :documentId
        order by v.version desc
        """,
    )
    fun findAllByDocument(
        @Param("documentId") documentId: UUID,
        pageable: Pageable,
    ): List<ConfigurationVersion>

    @Query(
        """
        select v from ConfigurationVersion v
        where v.documentId = :documentId and v.version = :version
        """,
    )
    fun findByDocumentAndVersion(
        @Param("documentId") documentId: UUID,
        @Param("version") version: Long,
    ): Optional<ConfigurationVersion>

    @Query(
        """
        select coalesce(max(v.version), 0) from ConfigurationVersion v
        where v.documentId = :documentId
        """,
    )
    fun maxVersion(
        @Param("documentId") documentId: UUID,
    ): Long
}

@Repository
interface ConfigurationSchemaRepository : JpaRepository<ConfigurationSchema, UUID> {
    fun findByKeyAndVersion(
        key: String,
        version: Int,
    ): Optional<ConfigurationSchema>

    @Query("select max(s.version) from ConfigurationSchema s where s.key = :key")
    fun maxVersionForKey(
        @Param("key") key: String,
    ): Int?
}

@Repository
interface ConfigurationAuditLogRepository : JpaRepository<ConfigurationAuditLog, ConfigurationAuditLogPk> {
    @Query(
        """
        select a from ConfigurationAuditLog a
        where a.documentId = :documentId
        order by a.pk.createdAt desc, a.pk.id desc
        """,
    )
    fun findAllByDocument(
        @Param("documentId") documentId: UUID,
        pageable: Pageable,
    ): List<ConfigurationAuditLog>
}

@Repository
interface IdempotencyRepository : JpaRepository<Idempotency, UUID> {
    @Modifying
    @Query("delete from Idempotency i where i.expiresAt < :cutoff")
    fun deleteAllByExpiresAtBefore(
        @Param("cutoff") cutoff: Instant,
    ): Long
}

@Repository
interface OutboxRepository : JpaRepository<OutboxEvent, UUID> {
    @Query("select o from OutboxEvent o where o.publishedAt is null order by o.createdAt asc")
    fun findTop100ByPublishedAtIsNullOrderByCreatedAtAsc(pageable: Pageable): List<OutboxEvent>
}

@Repository
interface InboxRepository : JpaRepository<InboxEvent, UUID> {
    fun existsByEventId(eventId: UUID): Boolean

    @Modifying
    @Query("delete from InboxEvent i where i.receivedAt < :cutoff")
    fun deleteAllByReceivedAtBefore(
        @Param("cutoff") cutoff: Instant,
    ): Long
}

@Repository
interface ChannelSubsetRepository : JpaRepository<ChannelSubset, UUID> {
    fun findAllByChannel(channel: String): List<ChannelSubset>
}
