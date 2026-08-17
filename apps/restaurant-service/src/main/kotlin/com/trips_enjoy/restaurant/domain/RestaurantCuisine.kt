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
 * One cuisine tag per restaurant (many-to-many). Mirrors
 * `restaurant.restaurant_cuisines` per docs/services/restaurant-service/ERD.md §3.
 * Composite PK on `(restaurant_id, cuisine)`.
 */
@Entity
@Table(name = "restaurant_cuisines", schema = "restaurant")
@IdClass(RestaurantCuisineKey::class)
class RestaurantCuisine(
    @Id @Column(name = "restaurant_id", nullable = false) val restaurantId: UUID,
    @Id @Column(nullable = false) val cuisine: String,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
) {
    init {
        require(cuisine.length in 1..50) { "cuisine length must be 1..50" }
    }
}

data class RestaurantCuisineKey(
    val restaurantId: UUID = UUID(0L, 0L),
    val cuisine: String = "",
) : Serializable