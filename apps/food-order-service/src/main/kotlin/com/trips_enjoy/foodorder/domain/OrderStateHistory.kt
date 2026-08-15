package com.trips_enjoy.foodorder.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Append-only audit of order state transitions. Mirrors
 * `food_order.order_state_history` per docs/services/food-order-service/ERD.md §3.
 *
 * SINGLE-UUID PK (not composite) per the canonical lift-forward
 * pattern from trip-service (avoiding the @IdClass blocker that
 * hit admin-service).
 */
@Entity
@Table(name = "order_state_history", schema = "food_order")
class OrderStateHistory(
    @Id val id: UUID,
    @Column(name = "subject_id", nullable = false) val subjectId: UUID,
    @Column(name = "subject_kind", nullable = false) val subjectKind: String = "order",
    @Column(name = "from_state") val fromState: String? = null,
    @Column(name = "to_state", nullable = false) val toState: String,
    @Column(name = "actor_kc_sub") val actorKcSub: UUID? = null,
    @Column(name = "actor_kind", nullable = false) val actorKind: String,
    @Column var reason: String? = null,
    @Column(name = "correlation_id", nullable = false) val correlationId: UUID,
    @Column(name = "occurred_at", nullable = false) val occurredAt: Instant = Instant.now(),
) {
    companion object {
        const val ACTOR_CUSTOMER = "customer"
        const val ACTOR_RESTAURANT = "restaurant"
        const val ACTOR_COURIER = "courier"
        const val ACTOR_ADMIN = "admin"
        const val ACTOR_SYSTEM = "system"
        const val ACTOR_DISPATCH = "dispatch"

        val VALID_ACTOR_KINDS: Set<String> = setOf(
            ACTOR_CUSTOMER, ACTOR_RESTAURANT, ACTOR_COURIER, ACTOR_ADMIN,
            ACTOR_SYSTEM, ACTOR_DISPATCH,
        )
        val VALID_SUBJECT_KINDS: Set<String> = setOf("order", "request")
    }

    init {
        require(actorKind in VALID_ACTOR_KINDS) { "unknown actor_kind $actorKind" }
        require(subjectKind in VALID_SUBJECT_KINDS) { "unknown subject_kind $subjectKind" }
    }
}