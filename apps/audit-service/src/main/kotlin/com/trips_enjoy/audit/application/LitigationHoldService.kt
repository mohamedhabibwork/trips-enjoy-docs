package com.trips_enjoy.audit.application

import com.trips_enjoy.audit.api.ApiException
import com.trips_enjoy.audit.api.LitigationHoldRequest
import com.trips_enjoy.audit.api.LitigationHoldResponse
import com.trips_enjoy.audit.api.toResponse
import com.trips_enjoy.audit.domain.LitigationHold
import com.trips_enjoy.audit.domain.LitigationHoldRepository
import com.trips_enjoy.audit.util.uuidV7
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Implements INTEGRATION §1.4 — append-only litigation hold creation. The
 * `litigation_hold` table is append-only per ERD §3 (extending a hold means
 * inserting a new row with a later `effective_from`).
 */
@Service
class LitigationHoldService(private val holds: LitigationHoldRepository) {

    @Transactional
    fun create(request: LitigationHoldRequest, actor: UUID): LitigationHoldResponse {
        // Reject empty selectors; at least one must be set so the hold has scope.
        if (request.tenant_id == null && request.subject_type == null && request.topic == null) {
            throw ApiException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "At least one of tenant_id, subject_type or topic must be provided",
            )
        }
        val hold = LitigationHold(
            id = uuidV7(),
            tenantId = request.tenant_id,
            subjectType = request.subject_type,
            subjectId = request.subject_id,
            topic = request.topic,
            reason = request.reason,
            effectiveFrom = request.effective_from,
            effectiveTo = request.effective_to,
            createdAt = java.time.Instant.now(),
            createdBy = actor,
        )
        return holds.save(hold).toResponse()
    }

    @Transactional(readOnly = true)
    fun list(pageable: org.springframework.data.domain.Pageable): List<LitigationHoldResponse> =
        holds.findAllByOrderByCreatedAtDesc(pageable).map { it.toResponse() }
}
