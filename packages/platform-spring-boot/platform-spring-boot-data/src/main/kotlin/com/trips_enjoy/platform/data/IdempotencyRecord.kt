package com.trips_enjoy.platform.data

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * Canonical idempotency record. The (actor_id, idempotency_key) pair is
 * unique; the record stores the cached response so replay can return the
 * exact same status + body without re-executing the handler.
 *
 * Three services had their own variants (identity `AuditAndOutbox.Idempotency`,
 * notification `IdempotencyRecord`, configuration `Idempotency`). This
 * canonical form aligns `actor_id` as the cross-cutting identifier.
 */
@Entity
@Table(name = "idempotency_record")
class IdempotencyRecord(

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    var id: UUID? = null,

    @Column(name = "actor_id", nullable = false, length = 64)
    var actorId: String,

    @Column(name = "idempotency_key", nullable = false, length = 128)
    var idempotencyKey: String,

    @Column(name = "request_hash", nullable = false, length = 128)
    var requestHash: String,

    @Column(name = "response_status", nullable = false)
    var responseStatus: Int,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body", columnDefinition = "jsonb")
    var responseBody: String? = null,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),
)
