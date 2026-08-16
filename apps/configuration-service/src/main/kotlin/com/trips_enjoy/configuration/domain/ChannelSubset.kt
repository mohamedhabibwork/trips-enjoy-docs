package com.trips_enjoy.configuration.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Per-channel view declaration. The `channel` is e.g. `customer_app_en`,
 * `driver_app_ar`. The `json_pointer` is an optional RFC 6901 pointer into
 * the value (e.g. `/theme/primary`) so clients only receive the subset of
 * nested fields they need (FR-014).
 */
@Entity
@Table(name = "channel_subsets", schema = "configuration")
class ChannelSubset(
    @Id val id: UUID,
    @Column(nullable = false) val channel: String,
    @Column(nullable = false) val key: String,
    @Column(name = "json_pointer") val jsonPointer: String? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
)
