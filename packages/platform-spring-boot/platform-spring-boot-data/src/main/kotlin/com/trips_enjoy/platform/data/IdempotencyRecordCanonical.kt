package com.trips_enjoy.platform.data

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * Canonical idempotency record per ADR-0027.
 *
 * The canonical `(actor_id, idempotency_key)` unique key isolates
 * per-actor namespaces so a key from one tenant cannot collide with
 * another. The [requestHash] is a SHA-256 of the canonical request
 * bytes; if the same key is replayed with a different body, the
 * caller surfaces a `422 IDEMPOTENCY_KEY_REUSED`.
 *
 * State machine:
 * - [State.PENDING]    - claim inserted, handler in flight
 * - [State.COMPLETED]  - response cached, replay returns the same body
 * - [State.RELEASED]   - claim abandoned (handler crashed); row kept
 *                        for audit but no longer satisfies replays
 *
 * Column reference:
 *
 * | column           | type        | notes                                   |
 * |------------------|-------------|-----------------------------------------|
 * | id               | UUID PK     | UUIDv7 row PK                           |
 * | actor_id         | UUID        | Per-actor namespace                     |
 * | idempotency_key  | UUID        | UUIDv7 client-supplied key              |
 * | request_hash     | CHAR(64)    | SHA-256 hex of canonical request        |
 * | response_status  | INT         | NULL while pending; HTTP status once    |
 * |                  |             | committed                               |
 * | response_body    | JSONB       | NULL while pending; response body once  |
 * |                  |             | committed                               |
 * | state            | VARCHAR(16) | PENDING | COMPLETED | RELEASED           |
 * | created_at       | TIMESTAMPTZ | Insert timestamp                        |
 * | expires_at       | TIMESTAMPTZ | 24h default; cleaned up by              |
 * |                  |             | [IdempotencyCleanupJob]                 |
 */
@Entity
@Table(name = "idempotency")
open class IdempotencyRecordCanonical(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID,

    @Column(name = "actor_id", nullable = false)
    var actorId: UUID,

    @Column(name = "idempotency_key", nullable = false)
    var idempotencyKey: UUID,

    @Column(name = "request_hash", nullable = false, length = 64)
    var requestHash: String,

    @Column(name = "response_status")
    var responseStatus: Int? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body", columnDefinition = "jsonb")
    var responseBody: String? = null,

    @Column(name = "state", nullable = false, length = 16)
    var state: String = State.PENDING.name,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,
) {
    init {
        require(requestHash.length == 64) { "request_hash must be a SHA-256 hex (64 chars)" }
        require(state.length in 1..16) { "state must fit in VARCHAR(16)" }
    }

    enum class State {
        PENDING,
        COMPLETED,
        RELEASED,
    }

    open fun complete(status: Int, body: String?, at: Instant) {
        responseStatus = status
        responseBody = body
        state = State.COMPLETED.name
    }

    open fun release() {
        state = State.RELEASED.name
    }
}