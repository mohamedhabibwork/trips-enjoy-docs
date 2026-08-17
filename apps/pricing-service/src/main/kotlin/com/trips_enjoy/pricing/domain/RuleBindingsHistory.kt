package com.trips_enjoy.pricing.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * Immutable, append-only history of every version of every rule
 * binding. Mirrors `pricing.rule_bindings_history` per
 * docs/services/pricing-service/ERD.md §3. Append-only (V3 trigger).
 *
 * The rollback pattern from admin-service's geo-config API does NOT
 * update this row in-place — it writes a new history row and points
 * `RuleBinding.superseded_by_id` at the new head. Same pattern as
 * configuration-service's `configuration_history`.
 */
@Entity
@Table(name = "rule_bindings_history", schema = "pricing")
class RuleBindingsHistory(
    @Id val id: UUID,
    @Column(name = "binding_id", nullable = false) val bindingId: UUID,
    @Column(nullable = false) val version: Int,
    @Column(nullable = false) val action: String,
    @Column(name = "actor_id", nullable = false) val actorId: UUID,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb") val payload: Map<String, Any?>,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
) {
    companion object {
        const val ACTION_CREATE = "create"
        const val ACTION_UPDATE = "update"
        const val ACTION_DISABLE = "disable"
        const val ACTION_ROLLBACK = "rollback"

        val VALID_ACTIONS: Set<String> = setOf(ACTION_CREATE, ACTION_UPDATE, ACTION_DISABLE, ACTION_ROLLBACK)
    }

    init {
        require(action in VALID_ACTIONS) { "unknown action $action" }
        require(version > 0) { "version must be > 0" }
    }
}