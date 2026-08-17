package com.trips_enjoy.restaurant.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.IdClass
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import java.util.UUID

/**
 * One free-form tag per restaurant (many-to-many). Mirrors
 * `restaurant.restaurant_tags` per docs/services/restaurant-service/ERD.md §3.
 * Composite PK on `(restaurant_id, tag)`.
 */
@Entity
@Table(name = "restaurant_tags", schema = "restaurant")
@IdClass(RestaurantTagKey::class)
class RestaurantTag(
    @Id @Column(name = "restaurant_id", nullable = false) val restaurantId: UUID,
    @Id @Column(nullable = false) val tag: String,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
) {
    init {
        require(tag.length <= 50) { "tag length must be <= 50" }
    }
}

data class RestaurantTagKey(
    val restaurantId: UUID = UUID(0L, 0L),
    val tag: String = "",
) : Serializable