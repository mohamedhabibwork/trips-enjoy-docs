package com.trips_enjoy.configuration.application

import com.fasterxml.jackson.databind.JsonNode
import com.trips_enjoy.configuration.api.ApiException
import com.trips_enjoy.configuration.domain.ChannelSubsetRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Per-channel client subset (FR-014 / INTEGRATION.md §1.8).
 *
 * Each `channel_subsets` row is a (channel, key, json_pointer) triple. The
 * `json_pointer` is an optional RFC 6901 pointer (e.g. `/theme/primary`)
 * that selects a nested field of the value. The filtered payload is what
 * the mobile / web client receives.
 */
@Service
class ChannelSubsetService(
    private val channelSubsetRepository: ChannelSubsetRepository,
    private val readService: ConfigurationReadService,
) {
    @Transactional(readOnly = true)
    fun subsetForChannel(
        channel: String,
        context: Map<String, String>,
        correlationId: UUID,
    ): Map<String, JsonNode> {
        val rows = channelSubsetRepository.findAllByChannel(channel)
        if (rows.isEmpty()) {
            throw ApiException(HttpStatus.NOT_FOUND, "CHANNEL_NOT_FOUND", "Channel '$channel' has no declared subsets")
        }
        // Resolve each key, then apply the json_pointer filter if present.
        val result = mutableMapOf<String, JsonNode>()
        val seenKeys = mutableSetOf<String>()
        for (row in rows) {
            if (!seenKeys.add(row.key)) continue
            val resolved = readService.resolve(row.key, context, correlationId)
            val pointer = rows.firstOrNull { it.key == row.key }?.jsonPointer
            result[row.key] = if (pointer == null) resolved.value else applyPointer(resolved.value, pointer)
        }
        return result
    }

    fun asOf(): Instant = Instant.now()

    private fun applyPointer(
        node: JsonNode,
        pointer: String,
    ): JsonNode {
        // RFC 6901 pointer e.g. "/theme/primary". Walk the segments.
        if (pointer.isEmpty() || pointer == "/") return node
        val segments = pointer.removePrefix("/").split("/")
        var current: JsonNode = node
        for (segment in segments) {
            val unescaped = segment.replace("~1", "/").replace("~0", "~")
            current = current.path(unescaped)
            if (current.isMissingNode || current.isNull) return current
        }
        return current
    }
}
