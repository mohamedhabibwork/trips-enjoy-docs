package com.trips_enjoy.foodorder.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "order_items", schema = "food_order")
class OrderItem(
    @Id val id: UUID,
    @Column(name = "order_id", nullable = false) val orderId: UUID,
    @Column(name = "menu_item_id", nullable = false) val menuItemId: UUID,
    @Column(nullable = false) var name: String,
    @Column(nullable = false) var quantity: Int = 1,
    @Column(name = "unit_price_minor", nullable = false) var unitPriceMinor: Long,
    @Column(name = "total_price_minor", nullable = false) var totalPriceMinor: Long,
    @Column(name = "special_instructions") var specialInstructions: String? = null,
    @Column(name = "row_version", nullable = false) var rowVersion: Long = 1L,
    @Column(name = "created_at", nullable = false) val createdAt: java.time.Instant = java.time.Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: java.time.Instant = java.time.Instant.now(),
    @Column(name = "created_by", nullable = false) val createdBy: UUID,
    @Column(name = "updated_by", nullable = false) var updatedBy: UUID = createdBy,
) {
    init {
        require(quantity >= 1) { "quantity must be >= 1" }
        require(unitPriceMinor >= 0) { "unit_price_minor must be >= 0" }
        require(totalPriceMinor >= 0) { "total_price_minor must be >= 0" }
    }
}

@Entity
@Table(name = "order_item_modifiers", schema = "food_order")
class OrderItemModifier(
    @Id val id: UUID,
    @Column(name = "order_item_id", nullable = false) val orderItemId: UUID,
    @Column(name = "modifier_id", nullable = false) val modifierId: UUID,
    @Column(nullable = false) var name: String,
    @Column(name = "price_delta_minor", nullable = false) var priceDeltaMinor: Long = 0L,
    @Column(name = "row_version", nullable = false) var rowVersion: Long = 1L,
    @Column(name = "created_at", nullable = false) val createdAt: java.time.Instant = java.time.Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: java.time.Instant = java.time.Instant.now(),
    @Column(name = "created_by", nullable = false) val createdBy: UUID,
    @Column(name = "updated_by", nullable = false) var updatedBy: UUID = createdBy,
)

@Entity
@Table(name = "order_item_addons", schema = "food_order")
class OrderItemAddon(
    @Id val id: UUID,
    @Column(name = "order_item_id", nullable = false) val orderItemId: UUID,
    @Column(name = "addon_id", nullable = false) val addonId: UUID,
    @Column(nullable = false) var name: String,
    @Column(name = "price_delta_minor", nullable = false) var priceDeltaMinor: Long = 0L,
    @Column(name = "row_version", nullable = false) var rowVersion: Long = 1L,
    @Column(name = "created_at", nullable = false) val createdAt: java.time.Instant = java.time.Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: java.time.Instant = java.time.Instant.now(),
    @Column(name = "created_by", nullable = false) val createdBy: UUID,
    @Column(name = "updated_by", nullable = false) var updatedBy: UUID = createdBy,
)