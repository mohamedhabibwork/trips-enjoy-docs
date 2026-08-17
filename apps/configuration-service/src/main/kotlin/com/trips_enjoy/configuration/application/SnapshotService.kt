package com.trips_enjoy.configuration.application

import com.trips_enjoy.configuration.api.ApiException
import com.trips_enjoy.configuration.domain.DocumentRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Bulk read for a service's known keys (FR-013 / INTEGRATION.md §1.7).
 *
 * Backed by the same precedence algorithm as the single-key read; this
 * returns a map of `{key -> resolvedValue}` for the supplied keys.
 */
@Service
class SnapshotService(
    private val documentRepository: DocumentRepository,
    private val readService: ConfigurationReadService,
) {
    @Transactional(readOnly = true)
    fun snapshot(
        keys: List<String>,
        context: Map<String, String>,
        correlationId: UUID,
    ): Map<String, ConfigurationReadService.ResolvedValue> {
        if (keys.isEmpty()) {
            throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "at least one key is required")
        }
        return keys.associateWith { key ->
            readService.resolve(key, context, correlationId)
        }
    }

    fun asOf(): Instant = Instant.now()
}
