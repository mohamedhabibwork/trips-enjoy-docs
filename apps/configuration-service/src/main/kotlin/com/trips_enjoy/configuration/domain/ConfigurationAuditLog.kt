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
 * Composite PK for the partitioned `configuration.audit_log` table.
 */
@Embeddable
data class ConfigurationAuditLogPk(
    @Column(name = "id", nullable = false) val id: UUID,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
) : Serializable

/**
 * Append-only local audit cache. UPDATE / DELETE are rejected at the
 * database level by a trigger (V4 migration + SEC-007).
 */
@Entity
@Table(name = "audit_log", schema = "configuration")
class ConfigurationAuditLog(
    @EmbeddedId val pk: ConfigurationAuditLogPk,
    @Column(name = "document_id", nullable = false) val documentId: UUID,
    @Column(nullable = false) val version: Long,
    @Column(nullable = false) val action: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "old_value", columnDefinition = "jsonb") val oldValue: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_value", columnDefinition = "jsonb") val newValue: String? = null,
    @Column(name = "actor_id", nullable = false) val actorId: UUID,
    @Column(nullable = false) val reason: String,
    @Column(name = "correlation_id", nullable = false) val correlationId: UUID,
    @Column(name = "client_ip") val clientIp: String? = null,
    @Column(name = "request_signature") val requestSignature: String? = null,
)
