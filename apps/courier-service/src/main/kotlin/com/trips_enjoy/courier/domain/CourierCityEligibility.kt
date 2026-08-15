package com.trips_enjoy.courier.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Per-city eligibility for a courier. Mirrors `courier.courier_city_eligibility`
 * per docs/services/courier-service/ERD.md §3. Many-to-many between
 * couriers and cities (geocoded zones in `geolocation-service`).
 *
 * Active eligibility is `revoked_at IS NULL`. The unique index on
 * `(courier_id, city_id) WHERE revoked_at IS NULL` enforces
 * "one active grant per courier per city".
 */
@Entity
@Table(name = "courier_city_eligibility", schema = "courier")
class CourierCityEligibility(
    @Id val id: UUID,
    @Column(name = "courier_id", nullable = false) val courierId: UUID,
    @Column(name = "city_id", nullable = false) val cityId: UUID,
    @Column(name = "granted_at", nullable = false) val grantedAt: Instant = Instant.now(),
    @Column(name = "revoked_at") var revokedAt: Instant? = null,
    @Column(name = "granted_by", nullable = false) val grantedBy: UUID,
    @Column(name = "revoked_by") var revokedBy: UUID? = null,
    @Column var notes: String? = null,
    @Column(name = "row_version", nullable = false) var rowVersion: Long = 1L,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
    @Column(name = "created_by", nullable = false) val createdBy: UUID,
    @Column(name = "updated_by", nullable = false) var updatedBy: UUID,
) {
    fun revoke(actorId: UUID, at: Instant) {
        check(revokedAt == null) { "eligibility already revoked" }
        revokedAt = at
        revokedBy = actorId
        updatedAt = at
        rowVersion += 1
    }

    fun isActive(at: Instant = Instant.now()): Boolean =
        revokedAt == null && grantedAt <= at
}