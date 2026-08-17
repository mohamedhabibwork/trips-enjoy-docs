package com.trips_enjoy.configuration.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * Current "head" of a configuration key — the most recent active version.
 * Soft-deleted via `deactivated_at`; the prior version remains in
 * `configuration.versions` forever (FR-003, ERD §3).
 */
@Entity
@Table(name = "documents", schema = "configuration")
class Document(
    @Id val id: UUID,
    @Column(nullable = false, unique = true) val key: String,
    @Column(name = "tenant_id", nullable = false) val tenantId: String = "global",
    @Column(name = "current_version", nullable = false) var currentVersion: Long = 0,
    @Column(name = "schema_id", nullable = false) var schemaId: UUID,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb") var value: String? = null,
    @Column(name = "value_type", nullable = false) var valueType: String,
    @Column(name = "deactivated_at") var deactivatedAt: Instant? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
    @Column(name = "created_by", nullable = false) val createdBy: UUID,
    @Column(name = "updated_by", nullable = false) var updatedBy: UUID,
)
