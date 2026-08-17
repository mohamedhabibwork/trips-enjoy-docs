package com.trips_enjoy.trip.domain

import com.trips_enjoy.platform.data.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * The trip request aggregate — the rider's order before dispatch.
 * Mirrors `trip.request` per docs/services/trip-service/ERD.md §3.
 *
 * Single-UUID PK (NOT composite) to avoid the Spring Data JPA + Kotlin
 * type-inference blocker that hit admin-service's `@IdClass` design
 * (see the uber-admin-service memory entry). The `status` column
 * tracks the request lifecycle: draft → priced → submitted →
 * matching → rejected/cancelled/expired → converted (to a Trip).
 *
 * Phase C (platform DRY): extends [BaseEntity] so `id`, `createdAt`,
 * `updatedAt`, `createdBy`, `updatedBy`, `version`, and `deletedAt` are
 * inherited from the platform canonical shape. The corresponding
 * column migration is V6 (`created_by` / `updated_by` `UUID` ->
 * `VARCHAR(255)`, `row_version` -> `version`).
 */
@Entity
@Table(name = "request", schema = "trip")
class Request(
    @Column(name = "rider_id", nullable = false) val riderId: UUID,
    @Column(name = "city_id") val cityId: UUID? = null,
    @Column(name = "origin_zone_id") val originZoneId: UUID? = null,
    @Column(name = "destination_zone_id") val destinationZoneId: UUID? = null,
    @Column(name = "ride_type", nullable = false) var rideType: String = RIDE_TYPE_STANDARD,
    @Column(nullable = false) var status: String = STATUS_DRAFT,
    @Column(name = "fare_id") var fareId: UUID? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "quote_snapshot", columnDefinition = "jsonb") var quoteSnapshot: Map<String, Any?>? = null,
    @Column(name = "correlation_id", nullable = false) var correlationId: UUID = UUID.randomUUID(),
    @Column(name = "idempotency_key") val idempotencyKey: String? = null,
    @Column(name = "requested_at", nullable = false) val requestedAt: Instant = Instant.now(),
    @Column(name = "expires_at") var expiresAt: Instant? = null,
) : BaseEntity() {
    companion object {
        const val RIDE_TYPE_STANDARD = "standard"
        const val RIDE_TYPE_XL = "xl"
        const val RIDE_TYPE_COMFORT = "comfort"
        const val RIDE_TYPE_POOL = "pool"
        const val RIDE_TYPE_PREMIUM = "premium"
        const val RIDE_TYPE_VAN = "van"
        const val RIDE_TYPE_ACCESSIBLE = "accessible"

        const val STATUS_DRAFT = "draft"
        const val STATUS_PRICED = "priced"
        const val STATUS_SUBMITTED = "submitted"
        const val STATUS_MATCHING = "matching"
        const val STATUS_REJECTED = "rejected"
        const val STATUS_CANCELLED = "cancelled"
        const val STATUS_EXPIRED = "expired"
        const val STATUS_CONVERTED = "converted"

        val VALID_RIDE_TYPES: Set<String> = setOf(
            RIDE_TYPE_STANDARD, RIDE_TYPE_XL, RIDE_TYPE_COMFORT,
            RIDE_TYPE_POOL, RIDE_TYPE_PREMIUM, RIDE_TYPE_VAN,
            RIDE_TYPE_ACCESSIBLE,
        )
        val VALID_STATUSES: Set<String> = setOf(
            STATUS_DRAFT, STATUS_PRICED, STATUS_SUBMITTED, STATUS_MATCHING,
            STATUS_REJECTED, STATUS_CANCELLED, STATUS_EXPIRED, STATUS_CONVERTED,
        )
    }

    init {
        require(rideType in VALID_RIDE_TYPES) { "unknown ride_type $rideType" }
        require(status in VALID_STATUSES) { "unknown status $status" }
    }

    fun price(fareId: UUID, snapshot: Map<String, Any?>, at: Instant) {
        check(status == STATUS_DRAFT) { "cannot price request in status $status" }
        this.fareId = fareId
        this.quoteSnapshot = snapshot
        this.status = STATUS_PRICED
        updatedAt = at
        version += 1
    }

    fun submit(at: Instant) {
        check(status in setOf(STATUS_DRAFT, STATUS_PRICED)) { "cannot submit in status $status" }
        status = STATUS_SUBMITTED
        updatedAt = at
        version += 1
    }

    fun convert(at: Instant) {
        check(status in setOf(STATUS_SUBMITTED, STATUS_MATCHING)) { "cannot convert in status $status" }
        status = STATUS_CONVERTED
        updatedAt = at
        version += 1
    }

    fun cancel(reason: String, at: Instant) {
        check(status != STATUS_CONVERTED) { "cannot cancel a converted request" }
        status = STATUS_CANCELLED
        updatedAt = at
        version += 1
    }
}