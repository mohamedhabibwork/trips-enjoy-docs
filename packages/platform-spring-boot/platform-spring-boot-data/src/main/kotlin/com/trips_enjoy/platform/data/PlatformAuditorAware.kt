package com.trips_enjoy.platform.data

import org.springframework.data.domain.AuditorAware
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.util.Optional

/**
 * Pulls the JWT `sub` claim from the current [SecurityContextHolder] for
 * JPA auditing fields. Falls back to `"system"` when no authenticated user
 * is present (e.g. background jobs, scheduled tasks).
 */
class PlatformAuditorAware : AuditorAware<String> {
    override fun getCurrentAuditor(): Optional<String> {
        val auth = SecurityContextHolder.getContext().authentication ?: return Optional.of("system")
        if (auth is JwtAuthenticationToken) {
            val jwt: Jwt = auth.token
            return Optional.of(jwt.subject)
        }
        return Optional.ofNullable(auth.name).or { Optional.of("system") }
    }
}
