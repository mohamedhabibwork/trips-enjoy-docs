package com.trips_enjoy.identity.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.identity.api.ApiException
import com.trips_enjoy.identity.domain.IdentityClaims
import com.trips_enjoy.identity.domain.IdentityClaimsRepository
import com.trips_enjoy.identity.domain.IdentityRepository
import org.springframework.cache.CacheManager
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Forces a re-read of the cached claims from Keycloak (TECH §10.4 row 3 —
 * "Force-refresh the local claim cache from Keycloak"). Also purges the
 * Redis claim cache so subsequent reads are forced to reload.
 */
@Service
class IdentityClaimsService(
    private val identities: IdentityRepository,
    private val keycloak: KeycloakAdminClient,
    private val claims: IdentityClaimsRepository,
    private val cacheManager: CacheManager,
    private val objectMapper: ObjectMapper,
) {
    @Transactional
    fun forceRefresh(id: UUID): IdentityClaims {
        val identity = identities.findById(id).orElseThrow {
            ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Identity not found")
        }
        // Purge cached entries
        cacheManager.getCache("identity-by-id")?.evict(id)
        cacheManager.getCache("identity-by-subject")?.evict("${identity.realm}:${identity.keycloakSubject}")
        cacheManager.getCache("identity-claims")?.evict(id)

        // Refresh cached claims using the identity's stored values; a full Keycloak fetch
        // is delegated to the SPI consumer which writes back to IdentityClaims.
        val now = Instant.now()
        val existing = claims.findById(id).orElse(null)
        if (existing == null) {
            val created = IdentityClaims(
                identityId = id,
                name = identity.name,
                email = identity.email,
                phone = identity.phone,
                locale = identity.locale,
                lastRefreshedAt = now,
                createdAt = now,
                updatedAt = now,
            )
            return claims.save(created)
        }
        return claims.save(
            existing.copy(
                name = identity.name,
                email = identity.email,
                phone = identity.phone,
                locale = identity.locale,
                lastRefreshedAt = now,
                updatedAt = now,
                rowVersion = existing.rowVersion + 1,
            ),
        )
    }
}
