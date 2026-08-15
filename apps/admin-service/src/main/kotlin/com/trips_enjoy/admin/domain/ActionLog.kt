package com.trips_enjoy.admin.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.io.Serializable
import java.time.Instant
import java.util.UUID

/**
 * The admin action_log aggregate — every admin action captured as
 * one row. Mirrors `admin.action_log` per
 * docs/services/admin-service/ERD.md §3.
 *
 * Composite PK on (id, occurred_at). The composite PK makes the
 * partition pruning cheap and the time range queries O(partition
 * count) instead of O(table size).
 *
 * Action types are strings (length 1..100). Actor kinds are restricted
 * to `admin / owner / staff / system / model`. The `payload` JSONB
 * captures the per-action state.
 *
 * Append-only — the partition maintenance job doesn't drop partitions
 * more aggressively than 7 years (per data retention).
 */
@Entity
@Table(name = "action_log", schema = "admin")
@IdClass(ActionLogKey::class)
class ActionLog(
    @Id val id: UUID,
    @Column(name = "action_type", nullable = false) val actionType: String,
    @Column(name = "actor_kc_sub", nullable = false) val actorKcSub: UUID,
    @Column(name = "actor_kind", nullable = false) val actorKind: String,
    @Column(name = "subject_kind") val subjectKind: String? = null,
    @Column(name = "subject_id") val subjectId: UUID? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb") var payload: Map<String, Any?>? = null,
    @Column var reason: String? = null,
    @Column(name = "signature_id") var signatureId: UUID? = null,
    @Column(name = "correlation_id", nullable = false) val correlationId: UUID,
    @Column(name = "break_glass_id") var breakGlassId: UUID? = null,
    @Column(name = "occurred_at", nullable = false) val occurredAt: Instant = Instant.now(),
) {
    companion object {
        const val ACTOR_ADMIN = "admin"
        const val ACTOR_OWNER = "owner"
        const val ACTOR_STAFF = "staff"
        const val ACTOR_SYSTEM = "system"
        const val ACTOR_MODEL = "model"

        val VALID_ACTOR_KINDS: Set<String> = setOf(
            ACTOR_ADMIN, ACTOR_OWNER, ACTOR_STAFF, ACTOR_SYSTEM, ACTOR_MODEL,
        )
    }

    init {
        require(actorKind in VALID_ACTOR_KINDS) { "unknown actor_kind $actorKind" }
        require(actionType.length in 1..100) { "action_type length must be 1..100" }
    }
}

data class ActionLogKey(
    val id: UUID = UUID(0L, 0L),
    val occurredAt: Instant = Instant.EPOCH,
) : Serializable