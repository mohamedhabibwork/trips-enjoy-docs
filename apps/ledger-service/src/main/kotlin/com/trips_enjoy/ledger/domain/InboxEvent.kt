package com.trips_enjoy.ledger.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Consumer-side dedup. Mirrors audit-service / identity-service.
 */
@Entity
@Table(name = "inbox", schema = "ledger")
class InboxEvent(
    @Id
    @Column(name = "event_id")
    val eventId: UUID,

    @Column(nullable = false)
    val topic: String,

    @Column(name = "received_at", nullable = false)
    val receivedAt: Instant = Instant.now(),

    @Column(name = "processed_at")
    var processedAt: Instant? = null,

    @Column
    var error: String? = null,
)
