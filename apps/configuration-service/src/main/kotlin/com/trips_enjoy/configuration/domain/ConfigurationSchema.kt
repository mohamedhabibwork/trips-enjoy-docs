package com.trips_enjoy.configuration.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * Declared JSON Schema for a configuration key.
 * Versioned per key — never edited in place; a schema change is a new
 * version row (ERD §3 + DATA-002).
 */
@Entity
@Table(name = "schemas", schema = "configuration")
class ConfigurationSchema(
    @Id val id: UUID,
    @Column(nullable = false) val key: String,
    @Column(nullable = false) val version: Int,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb") val jsonSchema: String,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "created_by", nullable = false) val createdBy: UUID,
)
