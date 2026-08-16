package com.trips_enjoy.configuration.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.configuration.api.ApiException
import com.trips_enjoy.configuration.domain.ConfigurationVersionRepository
import com.trips_enjoy.configuration.domain.Document
import com.trips_enjoy.configuration.domain.DocumentRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Read path for configuration documents (FR-001 / FR-002 / FR-020).
 *
 * The dominant path is `GET /v1/configurations/{key}` with an evaluation
 * context (city, ride_type, customer_segment, etc.). Resolution follows
 * the documented precedence:
 *   user > restaurant > branch > merchant > ride_type > zone > city
 *   > country > segment > tenant > global
 *
 * The "head" value of a document is the `(scope_type=global, scope_id=null)`
 * row. Per-scope overrides are additional version rows whose `scope_type`
 * + `scope_id` matches the request context. The most-specific match wins.
 */
@Service
class ConfigurationReadService(
    private val documentRepository: DocumentRepository,
    private val versionRepository: ConfigurationVersionRepository,
    private val redis: StringRedisTemplate,
    private val mapper: ObjectMapper,
    @Value("\${configuration-service.read-cache.ttl-seconds:300}")
    private val cacheTtlSeconds: Long,
) {
    data class ResolvedValue(
        val key: String,
        val value: JsonNode,
        val matchedScopeType: String,
        val matchedScopeId: String?,
        val version: Long,
        val schemaVersion: Int,
        val resolvedAt: Instant,
        val correlationId: UUID,
    )

    /**
     * Evaluate the precedence chain against the request context.
     * The context is a Map<scopeType, scopeId>; the order is the documented
     * precedence (high -> low).
     */
    fun resolve(
        key: String,
        context: Map<String, String>,
        correlationId: UUID,
    ): ResolvedValue {
        val document =
            documentRepository.findByKey(key).orElseThrow {
                ApiException(HttpStatus.NOT_FOUND, "CONFIG_KEY_NOT_FOUND", "Configuration key '$key' not found")
            }
        if (document.deactivatedAt != null) {
            throw ApiException(HttpStatus.NOT_FOUND, "CONFIG_KEY_NOT_FOUND", "Configuration key '$key' is deactivated")
        }

        // Cache key: (tenant_id, key, version). The "version" here is the
        // document's current head — a cache hit on the same head means we
        // can skip the resolution algorithm entirely.
        val cacheKey = "cache:${document.tenantId}:$key:${document.currentVersion}"
        val cached = redis.opsForValue().get(cacheKey)
        if (cached != null) {
            val cachedNode = mapper.readTree(cached)
            return ResolvedValue(
                key = key,
                value = cachedNode,
                matchedScopeType = "global",
                matchedScopeId = null,
                version = document.currentVersion,
                schemaVersion = 1,
                resolvedAt = Instant.now(),
                correlationId = correlationId,
            )
        }

        // Precedence chain. Highest priority first.
        val precedence =
            listOf(
                "user",
                "restaurant",
                "branch",
                "merchant",
                "ride_type",
                "zone",
                "city",
                "country",
                "segment",
                "tenant",
                "global",
            )
        for (scopeType in precedence) {
            val scopeId = context[scopeType] ?: if (scopeType == "global") null else continue
            val match =
                versionRepository
                    .findAllByDocument(
                        document.id,
                        org.springframework.data.domain.PageRequest
                            .of(0, 1),
                    ).firstOrNull { it.scopeType == scopeType && it.scopeId == scopeId }
            if (match != null) {
                val value = mapper.readTree(match.value ?: "null")
                val result =
                    ResolvedValue(
                        key = key,
                        value = value,
                        matchedScopeType = scopeType,
                        matchedScopeId = scopeId,
                        version = match.version,
                        schemaVersion = 1,
                        resolvedAt = Instant.now(),
                        correlationId = correlationId,
                    )
                if (cacheTtlSeconds > 0) {
                    redis.opsForValue().set(
                        cacheKey,
                        mapper.writeValueAsString(value),
                        Duration.ofSeconds(cacheTtlSeconds),
                    )
                }
                return result
            }
        }

        throw ApiException(HttpStatus.NOT_FOUND, "CONFIG_KEY_NOT_FOUND", "Configuration key '$key' has no value")
    }

    /**
     * Invalidate the Redis cache entry for a (tenant, key) pair. Called
     * after every write so subsequent reads see the new value.
     */
    @Transactional
    fun invalidate(document: Document) {
        val cacheKey = "cache:${document.tenantId}:${document.key}:${document.currentVersion}"
        redis.delete(cacheKey)
    }
}
