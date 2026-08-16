package com.trips_enjoy.customer.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * Append-only audit log of every customer state change. UPDATE and
 * DELETE are rejected by the database trigger
 * `customer_audit_log_append_only` (V4). The `before` / `after` JSONB
 * snapshots are stored as serialized JSON so the log is self-describing.
 */
@Entity
@Table(name = "customer_audit_log", schema = "customer")
class CustomerAuditLog(
    @Id val id: UUID,
    @Column(name = "customer_id", nullable = false) val customerId: UUID,
    @Column(nullable = false) val action: String,
    @Column(name = "actor") val actor: UUID? = null,
    @Column(name = "actor_type", nullable = false) val actorType: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before", columnDefinition = "jsonb") val before: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after", columnDefinition = "jsonb") val after: String? = null,
    @Column(name = "reason") val reason: String? = null,
    @Column(name = "correlation_id") val correlationId: UUID? = null,
    @Column(name = "occurred_at", nullable = false) val occurredAt: Instant = Instant.now(),
)
