package com.trips_enjoy.admin.domain

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
 * The admin action_log aggregate — every admin action captured as
 * one row. Mirrors `admin.action_log` per
 * docs/services/admin-service/ERD.md §3.
 *
 * Uses **`@EmbeddedId` + `@Embeddable`** (the modern Hibernate 7
 * pattern) rather than `@IdClass`. This avoids the Spring Data JPA +
 * Kotlin type-inference blocker that hit the original `@IdClass`
 * design (see the uber-admin-service memory entry).
 *
 * Composite PK on (id, occurred_at) makes partition pruning cheap and
 * time-range queries O(partition count) instead of O(table size).
 * Action types are strings (length 1..100). Actor kinds are restricted
 * to `admin / owner / staff / system / model`. The `payload` JSONB
 * captures the per-action state. Append-only.
 */
@Entity
@Table(name = "action_log", schema = "admin")
class ActionLog(
    @EmbeddedId val id: ActionLogKey,
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

/**
 * EmbeddedId for [ActionLog]. The `data class` + `Serializable`
 * contract is required by `@EmbeddedId` for Hibernate's bytecode
 * enhancement to generate a proper equals/hashCode.
 *
 * `occurredAt` defaults to `Instant.EPOCH` so the [ActionLog]
 * constructor can be invoked with `ActionLogKey()` in tests without
 * needing to supply every field; the migration always sets the
 * column to `now()` so production code overrides this via
 * `ActionLogKey(id = UUID.randomUUID(), occurredAt = Instant.now())`.
 */
@Embeddable
data class ActionLogKey(
    val id: UUID = UUID(0L, 0L),
    val occurredAt: Instant = Instant.EPOCH,
) : Serializable