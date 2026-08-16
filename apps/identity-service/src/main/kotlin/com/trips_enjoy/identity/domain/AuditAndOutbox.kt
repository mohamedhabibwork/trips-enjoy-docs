package com.trips_enjoy.identity.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "identity_audit_log", schema = "identity")
class IdentityAuditLog(
    @Id val id: UUID,
    @Column(name = "identity_id", nullable = false) val identityId: UUID,
    @Column(nullable = false) val action: String,
    @Column(nullable = false) val actor: UUID,
    @Column(name = "actor_type", nullable = false) val actorType: String,
    val reason: String? = null,
    @Column(name = "correlation_id") val correlationId: UUID? = null,
    @Column(name = "occurred_at", nullable = false) val occurredAt: Instant = Instant.now(),
    @Column(name = "role") val role: String? = null,
    @Column(name = "preset") val preset: String? = null,
    @Column(name = "cosigner") val cosigner: UUID? = null,
    @Column(name = "break_glass", nullable = false) val breakGlass: Boolean = false,
    @Column(name = "signature") val signature: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before", columnDefinition = "jsonb") val before: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after", columnDefinition = "jsonb") val after: String? = null,
    @Column(name = "occurred_by_role") val occurredByRole: String? = null,
)

interface IdentityAuditLogRepository : org.springframework.data.jpa.repository.JpaRepository<IdentityAuditLog, UUID>

@Entity
@Table(name = "outbox", schema = "identity")
class OutboxEvent(
    @Id val id: UUID,
    @Column(name = "aggregate_type", nullable = false) val aggregateType: String,
    @Column(name = "aggregate_id", nullable = false) val aggregateId: UUID,
    @Column(nullable = false) val topic: String,
    @Column(name = "event_name", nullable = false) val eventName: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb") val payload: String,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "published_at") var publishedAt: Instant? = null,
    @Column(nullable = false) var attempts: Int = 0,
    @Column(name = "last_error") var lastError: String? = null,
)

interface OutboxEventRepository : org.springframework.data.jpa.repository.JpaRepository<OutboxEvent, UUID> {
    fun findTop100ByPublishedAtIsNullOrderByCreatedAtAsc(): List<OutboxEvent>
    fun findAllByTopicAndAggregateId(topic: String, aggregateId: UUID): List<OutboxEvent>
}

@Entity
@Table(name = "idempotency_keys", schema = "identity")
class IdempotencyRecord(
    @Id val id: UUID,
    @Column(nullable = false) val actor: UUID,
    @Column(name = "idempotency_key", nullable = false) val idempotencyKey: UUID,
    @Column(name = "request_hash", nullable = false) val requestHash: String,
    @Column(name = "response_status", nullable = false) val responseStatus: Int,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body", nullable = false, columnDefinition = "jsonb") val responseBody: String,
    @Column(name = "expires_at", nullable = false) val expiresAt: Instant,
)

interface IdempotencyRecordRepository : org.springframework.data.jpa.repository.JpaRepository<IdempotencyRecord, UUID> {
    fun findByActorAndIdempotencyKey(actor: UUID, idempotencyKey: UUID): IdempotencyRecord?
    fun deleteAllByExpiresAtBefore(now: Instant): Long
}

@Entity
@Table(name = "inbox", schema = "identity")
class InboxEvent(
    @Id val id: UUID,
    @Column(name = "event_id", nullable = false, unique = true) val eventId: UUID,
    @Column(nullable = false) val topic: String,
    @Column(name = "received_at", nullable = false) val receivedAt: Instant = Instant.now(),
)

interface InboxEventRepository : org.springframework.data.jpa.repository.JpaRepository<InboxEvent, UUID> {
    fun existsByEventId(eventId: UUID): Boolean
}

/**
 * Cached Keycloak user-profile claims. Backed by `identity.identity_claims`.
 * Per ERD §3.2; read path for `GET /v1/identities/{id}/claims` and the
 * introspection response.
 */
@Entity
@Table(name = "identity_claims", schema = "identity")
data class IdentityClaims(
    @Id
    @Column(name = "identity_id")
    val identityId: UUID,
    @Column val name: String? = null,
    @Column val email: String? = null,
    @Column val phone: String? = null,
    @Column val locale: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "mfa_methods", columnDefinition = "jsonb", nullable = false)
    val mfaMethods: String = "[]",
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    val amr: String = "[]",
    @Column(name = "last_refreshed_at", nullable = false)
    var lastRefreshedAt: Instant = Instant.now(),
    @Column(name = "row_version", nullable = false)
    var rowVersion: Long = 0,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)

interface IdentityClaimsRepository : org.springframework.data.jpa.repository.JpaRepository<IdentityClaims, UUID>

/**
 * Append-only audit history of every claim field change.
 * Backed by `identity.identity_claim_history` (range-partitioned by month).
 */
@Entity
@Table(name = "identity_claim_history", schema = "identity")
class IdentityClaimHistory(
    @Id val id: UUID,
    @Column(name = "identity_id", nullable = false) val identityId: UUID,
    @Column(nullable = false) val field: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "old_value", columnDefinition = "jsonb") val oldValue: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_value", columnDefinition = "jsonb") val newValue: String? = null,
    @Column(nullable = false) val source: String,
    @Column(name = "changed_at", nullable = false) val changedAt: Instant = Instant.now(),
    @Column(name = "changed_by", nullable = false) val changedBy: UUID,
)

interface IdentityClaimHistoryRepository : org.springframework.data.jpa.repository.JpaRepository<IdentityClaimHistory, UUID>

/**
 * Append-only history of admin role-grant/revoke actions.
 * Backed by `identity.role_assignment_history` (range-partitioned by month,
 * immutability trigger in V4).
 */
@Entity
@Table(name = "role_assignment_history", schema = "identity")
class RoleAssignmentHistory(
    @Id val id: UUID,
    @Column(name = "identity_id", nullable = false) val identityId: UUID,
    @Column(name = "kc_sub", nullable = false) val kcSub: String,
    @Column(nullable = false) val realm: String,
    @Column(nullable = false) val role: String,
    @Column(nullable = false) val action: String,
    @Column val preset: String? = null,
    @Column(nullable = false) val actor: UUID,
    @Column val cosigner: UUID? = null,
    @Column(name = "break_glass", nullable = false) val breakGlass: Boolean = false,
    @Column val signature: String? = null,
    @Column(name = "reason_code") val reasonCode: String? = null,
    @Column(name = "correlation_id") val correlationId: UUID? = null,
    @Column(nullable = false) val endpoint: String,
    @Column(name = "target_resource", nullable = false) val targetResource: String,
    @Column(name = "occurred_at", nullable = false) val occurredAt: Instant = Instant.now(),
)

interface RoleAssignmentHistoryRepository : org.springframework.data.jpa.repository.JpaRepository<RoleAssignmentHistory, UUID> {
    fun findAllByIdentityId(identityId: UUID): List<RoleAssignmentHistory>
}
