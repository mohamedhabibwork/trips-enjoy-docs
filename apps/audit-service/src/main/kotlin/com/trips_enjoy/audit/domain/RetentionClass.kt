package com.trips_enjoy.audit.domain

/**
 * Two retention classes per docs/services/audit-service/ERD.md §3 Constraints.
 * Persisted in the `events.retention_class` CHECK column.
 */
enum class RetentionClass {
    FINANCIAL,
    DEFAULT;

    val value: String get() = name.lowercase()

    companion object {
        fun fromWire(value: String): RetentionClass = when (value.lowercase()) {
            "financial" -> FINANCIAL
            "default" -> DEFAULT
            else -> DEFAULT
        }
    }
}
