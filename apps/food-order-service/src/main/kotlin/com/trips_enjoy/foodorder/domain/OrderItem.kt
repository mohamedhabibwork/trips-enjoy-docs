package com.trips_enjoy.foodorder.domain

import com.trips_enjoy.platform.data.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/**
 * Phase C (platform DRY): `OrderItem`, `OrderItemModifier`, and
 * `OrderItemAddon` extend [BaseEntity] so the `id`, `createdAt`,
 * `updatedAt`, `createdBy`, `updatedBy`, and `version` columns are
 * inherited from the platform canonical shape. The corresponding
 * column migration is V5 (`created_by` / `updated_by` `UUID` →
 * `VARCHAR(255)`, `row_version` → `version`). These three tables do
 * not own a `deleted_at` column; soft delete is service-wide via
 * `Request`/`Order` only.
 */
@Entity
@Table(name = "order_items", schema = "food_order")
class OrderItem(
    @Column(name = "order_id", nullable = false) val orderId: UUID,
    @Column(name = "menu_item_id", nullable = false) val menuItemId: UUID,
    @Column(nullable = false) var name: String,
    @Column(nullable = false) var quantity: Int = 1,
    @Column(name = "unit_price_minor", nullable = false) var unitPriceMinor: Long,
    @Column(name = "total_price_minor", nullable = false) var totalPriceMinor: Long,
    @Column(name = "special_instructions") var specialInstructions: String? = null,
) : BaseEntity() {
    init {
        require(quantity >= 1) { "quantity must be >= 1" }
        require(unitPriceMinor >= 0) { "unit_price_minor must be >= 0" }
        require(totalPriceMinor >= 0) { "total_price_minor must be >= 0" }
    }
}

@Entity
@Table(name = "order_item_modifiers", schema = "food_order")
class OrderItemModifier(
    @Column(name = "order_item_id", nullable = false) val orderItemId: UUID,
    @Column(name = "modifier_id", nullable = false) val modifierId: UUID,
    @Column(nullable = false) var name: String,
    @Column(name = "price_delta_minor", nullable = false) var priceDeltaMinor: Long = 0L,
) : BaseEntity()

@Entity
@Table(name = "order_item_addons", schema = "food_order")
class OrderItemAddon(
    @Column(name = "order_item_id", nullable = false) val orderItemId: UUID,
    @Column(name = "addon_id", nullable = false) val addonId: UUID,
    @Column(nullable = false) var name: String,
    @Column(name = "price_delta_minor", nullable = false) var priceDeltaMinor: Long = 0L,
) : BaseEntity()