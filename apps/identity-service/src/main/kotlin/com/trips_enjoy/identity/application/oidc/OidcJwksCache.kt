package com.trips_enjoy.identity.application.oidc

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Per-realm JWKS cache (RFC 7517) backed by Caffeine.
 *
 * Behaviour:
 * - TTL configurable via `identity.oidc.jwks-cache-ttl-seconds` (default 300s).
 * - On `kid` miss the cache is refreshed immediately (per INTEGRATION §8.3).
 * - Each realm gets its own cache entry (key = realm).
 */
@Component
class OidcJwksCache(
    private val mapper: ObjectMapper,
    @Value("\${identity.oidc.jwks-cache-ttl-seconds:300}")
    private val ttlSeconds: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val cache: Cache<String, JsonNode> = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
        .maximumSize(32)
        .build()

    fun get(realm: String): JsonNode? = cache.getIfPresent(realm)

    fun put(realm: String, jwks: JsonNode) {
        cache.put(realm, jwks)
    }

    fun invalidate(realm: String) {
        log.info("Invalidating JWKS cache for realm={}", realm)
        cache.invalidate(realm)
    }

    fun invalidateAll() {
        log.info("Invalidating JWKS cache for all realms")
        cache.invalidateAll()
    }
}
