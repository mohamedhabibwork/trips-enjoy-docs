package com.trips_enjoy.platform.lookup

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * Canonical [LookupType] entity. Defines a named category of lookups
 * (e.g. `payment.method`, `customer.segment`). A service may have many
 * [LookupType]s; each type has many [Lookup]s.
 */
@Entity
@Table(name = "lookup_type")
class LookupType(
    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    var id: UUID? = null,

    @Column(name = "code", nullable = false, unique = true, length = 128)
    var code: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "name", nullable = false, columnDefinition = "jsonb")
    var name: String,

    @Column(name = "is_system", nullable = false)
    var isSystem: Boolean = false,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)

/**
 * Canonical [Lookup] entity. A row in a [LookupType]; carries the
 * `code`, display labels, hierarchy (`parent_id`), and `is_public` flag.
 */
@Entity
@Table(name = "lookup")
class Lookup(
    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    var id: UUID? = null,

    @Column(name = "lookup_type_id", nullable = false)
    var lookupTypeId: UUID,

    @Column(name = "code", nullable = false, length = 128)
    var code: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "name", nullable = false, columnDefinition = "jsonb")
    var name: String,

    @Column(name = "value", length = 128)
    var value: String? = null,

    @Column(name = "parent_id")
    var parentId: UUID? = null,

    @Column(name = "sort_by", nullable = false)
    var sortBy: Int = 0,

    @Column(name = "is_public", nullable = false)
    var isPublic: Boolean = false,

    @Column(name = "is_deleted", nullable = false)
    var isDeleted: Boolean = false,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
