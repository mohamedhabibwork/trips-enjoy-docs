package com.trips_enjoy.restaurant.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * The restaurant audit chain — every state-machine mutation writes one
 * row capturing the actor, the actor_type, the reason_code, the
 * from/to states, and the correlation_id. Mirrors
 * `restaurant.restaurant_audit_log` per docs/services/restaurant-service/ERD.md §3.
 * Append-only (V3 trigger).
 *
 * Notable: this audit log captures BOTH admin actions (approve,
 * suspend, close) AND merchant-level cascade events
 * (merchant_suspend_cascade, merchant_reinstate_cascade,
 * merchant_close_cascade). The actor_type field distinguishes
 * `admin` / `owner` / `staff` / `system`.
 */
@Entity
@Table(name = "restaurant_audit_log", schema = "restaurant")
class RestaurantAuditLog(
    @Id val id: UUID,
    @Column(name = "restaurant_id", nullable = false) val restaurantId: UUID,
    @Column(nullable = false) val action: String,
    @Column(name = "actor_kc_sub") val actorKcSub: UUID? = null,
    @Column(name = "actor_type", nullable = false) val actorType: String,
    @Column(name = "reason_code") val reasonCode: String? = null,
    @Column(name = "reason_text", columnDefinition = "text") var reasonText: String? = null,
    @Column(name = "from_state") val fromState: String? = null,
    @Column(name = "to_state") val toState: String? = null,
    @Column(name = "signature_id") val signatureId: UUID? = null,
    @Column(name = "correlation_id", nullable = false) val correlationId: UUID,
    @Column(name = "occurred_at", nullable = false) val occurredAt: Instant = Instant.now(),
) {
    companion object {
        const val ACTION_APPROVE = "approve"
        const val ACTION_REJECT = "reject"
        const val ACTION_SUSPEND = "suspend"
        const val ACTION_REINSTATE = "reinstate"
        const val ACTION_CLOSE = "close"
        const val ACTION_ONLINE = "online"
        const val ACTION_OFFLINE = "offline"
        const val ACTION_SUBMIT = "submit"
        const val ACTION_RESUBMIT = "resubmit"
        const val ACTION_MERCHANT_SUSPEND_CASCADE = "merchant_suspend_cascade"
        const val ACTION_MERCHANT_REINSTATE_CASCADE = "merchant_reinstate_cascade"
        const val ACTION_MERCHANT_CLOSE_CASCADE = "merchant_close_cascade"

        val VALID_ACTIONS: Set<String> = setOf(
            ACTION_APPROVE, ACTION_REJECT, ACTION_SUSPEND, ACTION_REINSTATE,
            ACTION_CLOSE, ACTION_ONLINE, ACTION_OFFLINE, ACTION_SUBMIT,
            ACTION_RESUBMIT,
            ACTION_MERCHANT_SUSPEND_CASCADE, ACTION_MERCHANT_REINSTATE_CASCADE,
            ACTION_MERCHANT_CLOSE_CASCADE,
        )
        val VALID_ACTOR_TYPES: Set<String> = setOf("admin", "owner", "staff", "system")
    }

    init {
        require(action in VALID_ACTIONS) { "unknown audit action $action" }
        require(actorType in VALID_ACTOR_TYPES) { "unknown actor_type $actorType" }
    }
}