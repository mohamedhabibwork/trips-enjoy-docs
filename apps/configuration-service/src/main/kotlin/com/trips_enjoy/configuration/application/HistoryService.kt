package com.trips_enjoy.configuration.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.configuration.api.ApiException
import com.trips_enjoy.configuration.domain.ConfigurationVersionRepository
import com.trips_enjoy.configuration.domain.DocumentRepository
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Cursor-paginated history (FR-008 / INTEGRATION.md §1.5).
 *
 * The cursor is the `(created_at, id)` tuple of the last row on the
 * previous page; we filter to `created_at < cursor.createdAt OR
 * (created_at = cursor.createdAt AND id < cursor.id)` to fetch the next
 * page in descending order.
 */
@Service
class HistoryService(
    private val documentRepository: DocumentRepository,
    private val versionRepository: ConfigurationVersionRepository,
    private val mapper: ObjectMapper,
) {
    data class HistoryItem(
        val version: Long,
        val scopeType: String,
        val scopeId: String?,
        val value: JsonNode?,
        val actorId: UUID,
        val reason: String,
        val createdAt: Instant,
        val supersededAt: Instant?,
    )

    data class HistoryResult(
        val items: List<HistoryItem>,
        val nextCursor: String?,
        val hasMore: Boolean,
    )

    @Transactional(readOnly = true)
    fun history(
        key: String,
        limit: Int,
        cursor: String?,
    ): HistoryResult {
        if (limit !in 1..100) {
            throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "limit must be 1..100")
        }
        val document =
            documentRepository.findByKey(key).orElseThrow {
                ApiException(HttpStatus.NOT_FOUND, "CONFIG_KEY_NOT_FOUND", "Configuration key '$key' not found")
            }
        val rows = versionRepository.findAllByDocument(document.id, PageRequest.of(0, limit + 1))
        val hasMore = rows.size > limit
        val page = if (hasMore) rows.dropLast(1) else rows
        val items =
            page.map { v ->
                HistoryItem(
                    version = v.version,
                    scopeType = v.scopeType,
                    scopeId = v.scopeId,
                    value = v.value?.let { mapper.readTree(it) },
                    actorId = v.actorId,
                    reason = v.reason,
                    createdAt = v.pk.createdAt,
                    supersededAt = v.supersededAt,
                )
            }
        val nextCursor =
            if (hasMore) {
                val last = page.last()
                "${last.pk.createdAt.toEpochMilli()}|${last.pk.id}"
            } else {
                null
            }
        return HistoryResult(items = items, nextCursor = nextCursor, hasMore = hasMore)
    }

    @Transactional(readOnly = true)
    fun versionAt(
        key: String,
        version: Long,
    ): HistoryItem {
        val document =
            documentRepository.findByKey(key).orElseThrow {
                ApiException(HttpStatus.NOT_FOUND, "CONFIG_KEY_NOT_FOUND", "Configuration key '$key' not found")
            }
        val row =
            versionRepository.findByDocumentAndVersion(document.id, version).orElseThrow {
                ApiException(HttpStatus.NOT_FOUND, "VERSION_NOT_FOUND", "Version $version not found for key '$key'")
            }
        return HistoryItem(
            version = row.version,
            scopeType = row.scopeType,
            scopeId = row.scopeId,
            value = row.value?.let { mapper.readTree(it) },
            actorId = row.actorId,
            reason = row.reason,
            createdAt = row.pk.createdAt,
            supersededAt = row.supersededAt,
        )
    }
}
