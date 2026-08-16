package com.trips_enjoy.configuration.domain

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.io.Serializable
import java.time.Instant
import java.util.UUID

/**
 * Composite primary key for `configuration.versions` — partitioned by
 * `created_at`, so the PK must include the partition key (PostgreSQL rule).
 */
@Embeddable
data class ConfigurationVersionPk(
    @Column(name = "id", nullable = false) val id: UUID,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
) : Serializable

/**
 * Immutable per-document history row. One new row per write (FR-003).
 * The `value` is JSONB; the `cohort` field is used for staged rollouts
 * (FR-016); `effective_from` / `effective_to` enable time-windowed
 * overrides (FR-017).
 */
@Entity
@Table(name = "versions", schema = "configuration")
class ConfigurationVersion(
    @EmbeddedId val pk: ConfigurationVersionPk,
    @Column(name = "document_id", nullable = false) val documentId: UUID,
    @Column(nullable = false) val version: Long,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb") val value: String?,
    @Column(name = "scope_type", nullable = false) val scopeType: String,
    @Column(name = "scope_id") val scopeId: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb") val cohort: String? = null,
    @Column(name = "effective_from") val effectiveFrom: Instant? = null,
    @Column(name = "effective_to") val effectiveTo: Instant? = null,
    @Column(nullable = false) val reason: String,
    @Column(name = "correlation_id", nullable = false) val correlationId: UUID,
    @Column(name = "actor_id", nullable = false) val actorId: UUID,
    @Column(name = "client_ip") val clientIp: String? = null,
    @Column(name = "superseded_at") val supersededAt: Instant? = null,
)
