package com.trips_enjoy.identity.application.oidc

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Rewrites Keycloak's `/.well-known/openid-configuration` so that every
 * endpoint URL advertised in the discovery document points at identity-service
 * instead of Keycloak directly. This is what makes identity-service a usable
 * OIDC provider from the client's perspective.
 *
 * If `identity.oidc.rewrite-base-url` is empty (dev / smoke test), the rewriter
 * uses the literal request base passed in via `requestBaseUrl`. In production
 * the value is set to e.g. `https://identity.trips-enjoy.example.com`.
 */
@Component
class OidcDiscoveryRewriter(
    private val mapper: ObjectMapper,
    @Value("\${identity.oidc.rewrite-base-url:}")
    private val rewriteBaseUrl: String,
) {
    /**
     * Replace the host:port prefix of every Keycloak endpoint in the discovery
     * document with the configured identity-service base URL. Preserves
     * non-URL fields (issuer, grant_types_supported, etc.) verbatim — per
     * OIDC Core §3 clients validate `iss` against the issuer returned in
     * the discovery document.
     */
    fun rewrite(discovery: JsonNode, fallbackBaseUrl: String): JsonNode {
        val baseUrl = if (rewriteBaseUrl.isNotBlank()) rewriteBaseUrl else fallbackBaseUrl
        val rewritten = mapper.createObjectNode()
        discovery.fields().forEachRemaining { (key, value) ->
            if (key == "issuer") {
                rewritten.set<JsonNode>(key, value.deepCopy())
            } else if (value.isTextual && value.asText().startsWith("http")) {
                val path = extractPath(value.asText())
                rewritten.set<JsonNode>(key, mapper.valueToTree(baseUrl.trimEnd('/') + path))
            } else {
                rewritten.set<JsonNode>(key, value.deepCopy())
            }
        }
        return rewritten
    }

    private fun extractPath(absoluteUrl: String): String {
        // Strip scheme://host[:port]
        val withoutScheme = absoluteUrl.substringAfter("://")
        val slashIndex = withoutScheme.indexOf('/')
        return if (slashIndex >= 0) withoutScheme.substring(slashIndex) else "/"
    }
}
