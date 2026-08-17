package com.trips_enjoy.audit.application

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.audit.api.AuditEventDetail
import com.trips_enjoy.audit.api.AuditEventSummary
import com.trips_enjoy.audit.api.AuditSearchQuery
import com.trips_enjoy.audit.api.AuditSearchResponse
import com.trips_enjoy.audit.api.toSummary
import com.trips_enjoy.audit.domain.AuditEvent
import com.trips_enjoy.audit.domain.AuditEventRepository
import com.trips_enjoy.audit.domain.AuditReadLog
import com.trips_enjoy.audit.domain.AuditReadLogRepository
import com.trips_enjoy.audit.util.uuidV7
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Implements the read API (INTEGRATION §1.1, §1.2). Every read is recorded in
 * `audit.read_log` per FR--011 — the search row, the result count, and the
 * `reason` are persisted in the same transaction so an auditor can later
 * audit the auditors.
 */
@Service
class AuditSearchService(
    private val events: AuditEventRepository,
    private val readLog: AuditReadLogRepository,
    private val objectMapper: ObjectMapper,
) {

    @Transactional
    fun search(
        query: AuditSearchQuery,
        limit: Int,
        cursor: String?,
        reason: String,
        actorId: UUID,
        actorIp: String?,
        correlationId: UUID,
    ): AuditSearchResponse {
        val pageable: Pageable = PageRequest.of(0, limit + 1)
        val rows = events.search(
            topic = query.topic,
            tenantId = query.tenant_id,
            subjectType = query.subject_type,
            subjectId = query.subject_id,
            correlationId = query.correlation_id,
            from = query.from,
            to = query.to,
            pageable = pageable,
        )
        val (page, hasMore) = if (rows.size > limit) rows.dropLast(1) to true else rows to false
        val nextCursor = if (hasMore) page.lastOrNull()?.let { encodeCursor(it) } else null
        readLog.save(
            AuditReadLog(
                id = uuidV7(),
                actorId = actorId,
                actorIp = actorIp,
                query = objectMapper.writeValueAsString(query),
                resultCount = page.size,
                reason = reason,
                correlationId = correlationId,
            ),
        )
        return AuditSearchResponse(
            items = page.map { it.toSummary() },
            next_cursor = nextCursor,
            has_more = hasMore,
        )
    }

    @Transactional(readOnly = true)
    fun getById(id: UUID, actorId: UUID, actorIp: String?, reason: String, correlationId: UUID): AuditEventDetail {
        val event = events.findByEventId(id)
            .orElseThrow { com.trips_enjoy.audit.api.ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND", "Event $id not found") }
        readLog.save(
            AuditReadLog(
                id = uuidV7(),
                actorId = actorId,
                actorIp = actorIp,
                query = objectMapper.writeValueAsString(mapOf("event_id" to id.toString())),
                resultCount = 1,
                reason = reason,
                correlationId = correlationId,
            ),
        )
        return toDetail(event)
    }

    private fun toDetail(event: AuditEvent): AuditEventDetail = AuditEventDetail(
        id = event.id,
        event_id = event.eventId,
        event_name = event.eventName,
        schema_version = event.schemaVersion,
        occurred_at = event.occurredAt,
        received_at = event.receivedAt,
        producer = event.producer,
        tenant_id = event.tenantId,
        correlation_id = event.correlationId,
        causation_id = event.causationId,
        aggregate_type = event.aggregateType,
        aggregate_id = event.aggregateId,
        subject_type = event.subjectType,
        subject_id = event.subjectId,
        data = parseJson(event.data),
        headers = event.headers?.let { parseJson(it) },
        topic = event.topic,
        partition = event.partition,
        offset = event.offset,
        prev_hash = event.prevHash,
        hash = event.hash,
        retention_class = event.retentionClass,
        litigation_hold = event.litigationHold,
        retention_until = event.retentionUntil,
        created_at = event.createdAt,
    )

    private fun parseJson(payload: String): Map<String, Any?> = runCatching {
        objectMapper.readValue(payload, MAP_TYPE)
    }.getOrElse { emptyMap() }

    private fun encodeCursor(event: AuditEvent): String =
        java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("${event.createdAt.toEpochMilli()}|${event.id}".toByteArray())

    companion object {
        private val MAP_TYPE = object : TypeReference<Map<String, Any?>>() {}
    }
}
