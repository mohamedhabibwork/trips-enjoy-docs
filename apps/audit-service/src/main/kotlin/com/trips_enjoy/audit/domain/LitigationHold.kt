package com.trips_enjoy.audit.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Append-only registry of litigation holds. Per ERD §3 `LitigationHold`.
 *
 * To extend a hold, append a new row with a later `effective_from` and the
 * same selector columns. UPDATE/DELETE is rejected by the trigger in V3.
 */
@Entity
@Table(name = "litigation_hold", schema = "audit")
class LitigationHold(
    @Id
    val id: UUID,

    @Column(name = "tenant_id")
    val tenantId: String? = null,

    @Column(name = "subject_type")
    val subjectType: String? = null,

    @Column(name = "subject_id")
    val subjectId: UUID? = null,

    @Column
    val topic: String? = null,

    @Column(nullable = false)
    val reason: String,

    @Column(name = "effective_from", nullable = false)
    val effectiveFrom: Instant,

    @Column(name = "effective_to")
    val effectiveTo: Instant? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "created_by", nullable = false)
    val createdBy: UUID,
)
