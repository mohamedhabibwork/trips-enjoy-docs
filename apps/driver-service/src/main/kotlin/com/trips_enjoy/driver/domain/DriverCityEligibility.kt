package com.trips_enjoy.driver.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Per-city eligibility for a driver. Mirrors `driver.driver_city_eligibility`
 * per docs/services/driver-service/ERD.md §3. Many-to-many between
 * drivers and cities (geocoded zones in `geolocation-service`).
 *
 * Active eligibility is `revoked_at IS NULL`. The unique index on
 * `(driver_id, city_id) WHERE revoked_at IS NULL` enforces
 * "one active grant per driver per city". A re-grant after revoke
 * creates a fresh row.
 */
@Entity
@Table(name = "driver_city_eligibility", schema = "driver")
class DriverCityEligibility(
    @Id val id: UUID,
    @Column(name = "driver_id", nullable = false) val driverId: UUID,
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