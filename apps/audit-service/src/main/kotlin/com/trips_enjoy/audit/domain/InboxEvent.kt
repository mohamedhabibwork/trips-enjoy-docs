package com.trips_enjoy.audit.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Inbox row per SRS §15 — deduplicates consumed events on `event_id`. Mirrors
 * identity-service's `InboxEvent` shape (V3 there).
 */
@Entity
@Table(name = "inbox", schema = "audit")
class InboxEvent(
    @Id
    @Column(name = "event_id")
    val eventId: UUID,

    @Column(nullable = false)
    val topic: String,

    @Column(name = "received_at", nullable = false)
    val receivedAt: Instant = Instant.now(),

    @Column(name = "processed_at")
    val processedAt: Instant? = null,

    @Column
    val error: String? = null,
)
