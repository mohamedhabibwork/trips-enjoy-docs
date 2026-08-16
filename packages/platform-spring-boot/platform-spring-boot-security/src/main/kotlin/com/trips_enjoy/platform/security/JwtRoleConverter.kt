package com.trips_enjoy.platform.security

import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

/**
 * Maps Keycloak / JWT claims to Spring Security authorities.
 *
 * - `realm_access.roles[]` → `ROLE_<UPPER>`
 * - `resource_access.<client>.roles[]` → `ROLE_<CLIENT>_<UPPER>`
 * - `scope` / `scp` (space-delimited) → `SCOPE_<lower>`
 */
class JwtRoleConverter : Converter<Jwt, AbstractAuthenticationToken> {

    override fun convert(jwt: Jwt): AbstractAuthenticationToken {
        val authorities = mutableSetOf<GrantedAuthority>()

        @Suppress("UNCHECKED_CAST")
        val realmAccess = jwt.claims["realm_access"] as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val realmRoles = realmAccess?.get("roles") as? List<String> ?: emptyList()
        authorities += realmRoles.map { SimpleGrantedAuthority("ROLE_${it.uppercase()}") }

        @Suppress("UNCHECKED_CAST")
        val resourceAccess = jwt.claims["resource_access"] as? Map<String, Any?> ?: emptyMap()
        for ((client, access) in resourceAccess) {
            @Suppress("UNCHECKED_CAST")
            val roles = (access as? Map<String, Any?>)?.get("roles") as? List<String> ?: emptyList()
            authorities += roles.map { SimpleGrantedAuthority("ROLE_${client.uppercase()}_${it.uppercase()}") }
        }

        val scope = jwt.claims["scope"] as? String
            ?: jwt.claims["scp"] as? String
        if (scope != null) {
            authorities += scope.split(" ").filter { it.isNotBlank() }
                .map { SimpleGrantedAuthority("SCOPE_${it.lowercase()}") }
        }

        return JwtAuthenticationToken(jwt, authorities, jwt.subject)
    }
}
