package com.trips_enjoy.restaurant.domain

import com.trips_enjoy.platform.data.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * The restaurant brand aggregate — one row per public restaurant.
 * Mirrors `restaurant.restaurants` per docs/services/restaurant-service/ERD.md §3.
 *
 * 8-state lifecycle (per INTEGRATION §1.4–1.11):
 *   draft → pending_review → approved → online ↔ offline
 *                                              ↓
 *                                           suspended | closed | rejected
 *
 *   pending_review → resubmit → pending_review (loops back)
 *
 * Phase C (platform DRY): extends [BaseEntity] so the `id`,
 * `createdAt`, `updatedAt`, `createdBy`, `updatedBy`, `version`, and
 * `deletedAt` columns are inherited from the platform canonical
 * shape. The corresponding column migration is V6 (`created_by` /
 * `updated_by` `UUID` → `VARCHAR(255)`, `row_version` → `version`).
 *
 * Service-specific audit fields retained here:
 *   * `stateActorKcSub` (`UUID?`) — the Keycloak subject of the actor
 *     that drove the last state transition. Distinct from
 *     `BaseEntity.updatedBy` (a generic JWT `sub` populated by the
 *     platform auditor) because state-machine transitions can also be
 *     triggered by platform-internal actors (admin-service) whose
 *     `sub` is not a person.
 *   * `stateReasonCode` (`String?`) — the platform-level
 *     `X-Audit-Reason` header / workflow reason code captured on the
 *     last transition.
 */
@Entity
@Table(name = "restaurants", schema = "restaurant")
class Restaurant(
    @Column(name = "merchant_id", nullable = false) val merchantId: UUID,
    @Column(nullable = false) var name: String,
    @Column(nullable = false) var slug: String,
    @Column(nullable = false) var type: String,
    @Column var description: String? = null,
    @Column(name = "logo_file_id") var logoFileId: UUID? = null,
    @Column(name = "cover_file_id") var coverFileId: UUID? = null,
    @Column(nullable = false) var state: String = STATE_DRAFT,
    @Column(nullable = false) var online: Boolean = false,
    @Column(name = "auto_offline_enabled", nullable = false) var autoOfflineEnabled: Boolean = true,
    @Column(nullable = false) var avgRating: BigDecimal = BigDecimal.ZERO,
    @Column(name = "review_count", nullable = false) var reviewCount: Int = 0,
    @Column(name = "last_rating_update_at") var lastRatingUpdateAt: Instant? = null,
    @Column(name = "state_reason_code") var stateReasonCode: String? = null,
    @Column(name = "state_actor_kc_sub") var stateActorKcSub: UUID? = null,
    @Column(name = "state_changed_at") var stateChangedAt: Instant? = null,
) : BaseEntity() {
    companion object {
        const val STATE_DRAFT = "draft"
        const val STATE_PENDING_REVIEW = "pending_review"
        const val STATE_APPROVED = "approved"
        const val STATE_REJECTED = "rejected"
        const val STATE_ONLINE = "online"
        const val STATE_OFFLINE = "offline"
        const val STATE_SUSPENDED = "suspended"
        const val STATE_CLOSED = "closed"

        const val TYPE_RESTAURANT = "restaurant"
        const val TYPE_CAFE = "cafe"
        const val TYPE_BAKERY = "bakery"
        const val TYPE_CLOUD_KITCHEN = "cloud_kitchen"
        const val TYPE_FOOD_TRUCK = "food_truck"
        const val TYPE_OTHER = "other"

        val VALID_TYPES: Set<String> = setOf(
            TYPE_RESTAURANT, TYPE_CAFE, TYPE_BAKERY, TYPE_CLOUD_KITCHEN,
            TYPE_FOOD_TRUCK, TYPE_OTHER,
        )
    }

    init {
        require(type in VALID_TYPES) { "unknown restaurant type $type" }
        require(name.length in 1..120) { "name length must be 1..120" }
        require(slug.matches(Regex("^[a-z0-9](?:[a-z0-9-]{1,38}[a-z0-9])?$"))) {
            "slug must match ^[a-z0-9](?:[a-z0-9-]{1,38}[a-z0-9])?$ (got: $slug)"
        }
    }

    fun submit(actorKcSub: UUID, at: Instant) {
        check(state == STATE_DRAFT) { "cannot submit restaurant in state $state" }
        transitionTo(STATE_PENDING_REVIEW, "submitted_for_review", actorKcSub, at)
    }

    fun approve(actorKcSub: UUID, at: Instant) {
        check(state == STATE_PENDING_REVIEW) { "cannot approve restaurant in state $state" }
        transitionTo(STATE_APPROVED, "approved_by_admin", actorKcSub, at)
    }

    fun reject(reason: String, actorKcSub: UUID, at: Instant) {
        check(state == STATE_PENDING_REVIEW) { "cannot reject restaurant in state $state" }
        require(reason.isNotBlank()) { "rejection reason required" }
        transitionTo(STATE_REJECTED, reason, actorKcSub, at)
    }

    fun goOnline(actorKcSub: UUID, at: Instant) {
        check(state == STATE_APPROVED || state == STATE_OFFLINE) {
            "cannot go online in state $state"
        }
        transitionTo(STATE_ONLINE, "merchant_toggled_online", actorKcSub, at)
        online = true
    }

    fun goOffline(actorKcSub: UUID, at: Instant) {
        check(state == STATE_ONLINE) { "cannot go offline in state $state" }
        transitionTo(STATE_OFFLINE, "merchant_toggled_offline", actorKcSub, at)
        online = false
    }

    fun suspend(reason: String, actorKcSub: UUID, at: Instant) {
        check(state in setOf(STATE_APPROVED, STATE_ONLINE, STATE_OFFLINE)) {
            "cannot suspend restaurant in state $state"
        }
        require(reason.isNotBlank()) { "suspension reason required" }
        transitionTo(STATE_SUSPENDED, reason, actorKcSub, at)
        online = false
    }

    fun reinstate(actorKcSub: UUID, at: Instant) {
        check(state == STATE_SUSPENDED) { "cannot reinstate restaurant in state $state" }
        transitionTo(STATE_APPROVED, "reinstated_by_admin", actorKcSub, at)
    }

    fun close(actorKcSub: UUID, at: Instant) {
        check(state != STATE_CLOSED) { "restaurant already closed" }
        check(state != STATE_DRAFT) { "cannot close a draft restaurant" }
        transitionTo(STATE_CLOSED, "closed_by_owner", actorKcSub, at)
        online = false
    }

    fun resubmit(actorKcSub: UUID, at: Instant) {
        check(state in setOf(STATE_PENDING_REVIEW, STATE_REJECTED)) {
            "cannot resubmit restaurant in state $state"
        }
        transitionTo(STATE_PENDING_REVIEW, "resubmitted_for_review", actorKcSub, at)
    }

    fun cascadeSuspend(reason: String, actorKcSub: UUID, at: Instant) {
        check(state in setOf(STATE_APPROVED, STATE_ONLINE, STATE_OFFLINE)) {
            "cannot cascade-suspend restaurant in state $state"
        }
        transitionTo(STATE_SUSPENDED, "merchant_suspend_cascade: $reason", actorKcSub, at)
        online = false
    }

    fun cascadeReinstate(actorKcSub: UUID, at: Instant) {
        check(state == STATE_SUSPENDED) {
            "cannot cascade-reinstate restaurant in state $state"
        }
        transitionTo(STATE_APPROVED, "merchant_reinstate_cascade", actorKcSub, at)
    }

    fun cascadeClose(actorKcSub: UUID, at: Instant) {
        check(state != STATE_CLOSED) { "restaurant already closed" }
        transitionTo(STATE_CLOSED, "merchant_close_cascade", actorKcSub, at)
        online = false
    }

    fun applyRating(newRating: BigDecimal, at: Instant) {
        require(newRating.toDouble() in 1.0..5.0) { "rating must be 1.0..5.0" }
        val total = avgRating.multiply(BigDecimal(reviewCount)).add(newRating)
        reviewCount += 1
        avgRating = total.divide(BigDecimal(reviewCount), 2, java.math.RoundingMode.HALF_UP)
        lastRatingUpdateAt = at
        updatedAt = at
    }

    private fun transitionTo(newState: String, reasonCode: String, actorKcSub: UUID, at: Instant) {
        state = newState
        stateReasonCode = reasonCode
        stateActorKcSub = actorKcSub
        stateChangedAt = at
        updatedAt = at
    }
}
