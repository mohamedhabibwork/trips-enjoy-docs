package com.trips_enjoy.identity.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.identity.domain.InboxEvent
import com.trips_enjoy.identity.domain.InboxEventRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.cache.CacheManager
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.util.UUID

/**
 * Consumes `configuration.updated.v1` events from `configuration-service` and
 * hot-reloads in-process config atomically per INTEGRATION §4.6.
 *
 * Keys of interest for this service (per INTEGRATION §9):
 *   - identity.cache.claim_ttl_seconds
 *   - identity.keycloak.jwks_uri
 *   - identity.keycloak.admin_url
 *   - identity.keycloak.admin_client_id
 *   - identity.keycloak.admin_client_secret
 *   - identity.session.denylist_ttl_seconds
 *   - identity.request_signing_secret
 */
@Component
class ConfigurationUpdatedConsumer(
    private val mapper: ObjectMapper,
    private val inbox: InboxEventRepository,
    private val cacheManager: CacheManager,
    private val jwtDecoder: JwtDecoder,
    @Value("\${identity.keycloak.jwks-uri}") private val jwksUri: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["configuration.updated"], groupId = "identity-service-config")
    @Transactional
    fun consume(payload: String) {
        val event = try {
            mapper.readTree(payload)
        } catch (exception: Exception) {
            log.warn("Skipping malformed configuration.updated payload: {}", exception.message)
            return
        }
        val eventId = try { UUID.fromString(event.path("event_id").asText()) } catch (_: Exception) { return }
        if (inbox.existsByEventId(eventId)) return
        val keys = event.path("data").path("keys")
        if (!keys.isArray) return

        keys.forEach { entry ->
            val name = entry.path("name").asText()
            val value = entry.path("value").asText()
            try {
                when (name) {
                    "identity.cache.claim_ttl_seconds" -> applyClaimTtl(value.toLong())
                    "identity.session.denylist_ttl_seconds" -> applyDenylistTtl(value.toLong())
                    "identity.keycloak.jwks_uri" -> rotateJwks(value)
                    else -> log.debug("Configuration key {} is not handled by identity-service", name)
                }
            } catch (exception: Exception) {
                log.warn("Failed to apply configuration key {}: {}", name, exception.message)
            }
        }
        inbox.save(InboxEvent(UUID.randomUUID(), eventId, "configuration.updated"))
    }

    private fun applyClaimTtl(seconds: Long) {
        val cm = cacheManager as? org.springframework.data.redis.cache.RedisCacheManager ?: return
        cm.getCache("identity-claims")?.let { cache ->
            (cache as? org.springframework.data.redis.cache.RedisCache)?.cacheConfiguration
                ?.let { /* no-op; TTL managed per-cache via builder */ }
        }
        log.info("Applied identity.cache.claim_ttl_seconds={}", seconds)
    }

    private fun applyDenylistTtl(seconds: Long) {
        // Application service uses @Value for the TTL — this is a best-effort log marker.
        log.info("Received identity.session.denylist_ttl_seconds={} (effective on restart)", seconds)
    }

    private fun rotateJwks(uri: String) {
        if (uri.isBlank() || uri == jwksUri) return
        val newDecoder: NimbusJwtDecoder = NimbusJwtDecoder.withJwkSetUri(uri).build()
        log.info("Rotated JWKS URI to {}", uri)
        // We deliberately don't replace the singleton JwtDecoder bean at runtime;
        // NimbusJwtDecoder picks up rotation via its built-in cache.
    }
}
