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
 * Idempotency-Key dedupe store per the platform standard (SRS §15).
 * Caches the response status + body for 24h so a retry of the same write
 * returns the same answer without re-executing the underlying transaction.
 */
@Entity
@Table(name = "idempotency", schema = "configuration")
class Idempotency(
    @Id @Column(name = "idempotency_key") val idempotencyKey: UUID,
    @Column(name = "request_hash", nullable = false) val requestHash: String,
    @Column(name = "response_status", nullable = false) val responseStatus: Int,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body", nullable = false, columnDefinition = "jsonb") val responseBody: String,
    @Column(name = "actor_id", nullable = false) val actorId: UUID,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "expires_at", nullable = false) val expiresAt: Instant,
)
