package com.trips_enjoy.courier.domain

import com.trips_enjoy.platform.data.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
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
 *
 * Phase C (platform DRY): extends [BaseEntity]. See V6 migration
 * (`created_by` / `updated_by` `UUID` → `VARCHAR(255)`,
 * `row_version` → `version`). Note: `granted_by` and `revoked_by`
 * remain cross-service UUID references (DATA-003); they are NOT
 * covered by the BaseEntity migration — they represent
 * identity-service subject ids, not platform JWT `sub`.
 */
@Entity
@Table(name = "courier_city_eligibility", schema = "courier")
class CourierCityEligibility(
    @Column(name = "courier_id", nullable = false) val courierId: UUID,
    @Column(name = "city_id", nullable = false) val cityId: UUID,
    @Column(name = "granted_at", nullable = false) val grantedAt: Instant = Instant.now(),
    @Column(name = "revoked_at") var revokedAt: Instant? = null,
    @Column(name = "granted_by", nullable = false) val grantedBy: UUID,
    @Column(name = "revoked_by") var revokedBy: UUID? = null,
    @Column var notes: String? = null,
) : BaseEntity() {
    fun revoke(actorId: UUID, at: Instant) {
        check(revokedAt == null) { "eligibility already revoked" }
        revokedAt = at
        revokedBy = actorId
        updatedAt = at
        version += 1
    }

    fun isActive(at: Instant = Instant.now()): Boolean =
        revokedAt == null && grantedAt <= at
}
