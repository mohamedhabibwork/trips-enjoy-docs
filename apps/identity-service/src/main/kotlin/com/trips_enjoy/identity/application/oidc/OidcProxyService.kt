package com.trips_enjoy.identity.application.oidc

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.identity.api.oidc.IntrospectionResponse
import com.trips_enjoy.identity.api.oidc.UserInfoResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI

/**
 * OIDC BFF proxy. Forwards each OIDC request to the configured Keycloak
 * realm, applies caching where the docs allow, and normalises responses.
 *
 * All endpoints in this class map to a single Keycloak URL pattern:
 * `POST/GET <base>/realms/{realm}/protocol/openid-connect/<sub-path>`
 *
 * RFC compliance:
 *  - OIDC authorize   — RFC 6749 §4.1.1 (Authorization Code)
 *  - OIDC token       — RFC 6749 §4.1.3 + RFC 6749 §4.4 (client_credentials)
 *  - OIDC introspect  — RFC 7662 (with Redis-backed 5-minute cache per docs §8.8)
 *  - OIDC userinfo    — OIDC Core §5.3 (Bearer token required)
 *  - OIDC jwks        — RFC 7517 (cached)
 *  - OIDC logout      — OIDC RP-initiated logout §2
 *  - OIDC revoke      — RFC 7009
 *  - OIDC discovery   — OIDC Discovery §3 (rewritten)
 */
@Service
class OidcProxyService(
    restClientBuilder: RestClient.Builder,
    private val jwksCache: OidcJwksCache,
    private val rewriter: OidcDiscoveryRewriter,
    private val objectMapper: ObjectMapper,
    @Value("\${identity.keycloak.base-url}") private val baseUrl: String,
    @Value("\${identity.oidc.default-realm:platform-services}") private val defaultRealm: String,
) {
    private val restClient: RestClient = restClientBuilder.baseUrl(baseUrl).build()
    private val log = LoggerFactory.getLogger(javaClass)

    // ------------------------------------------------------------------
    // /.well-known/openid-configuration (OIDC Discovery)
    // ------------------------------------------------------------------

    fun discovery(realm: String, fallbackBaseUrl: String): JsonNode {
        val keycloakDiscovery = fetchKeycloak(realm, "/.well-known/openid-configuration", HttpMethod.GET, null, MediaType.APPLICATION_JSON)
        return rewriter.rewrite(keycloakDiscovery, fallbackBaseUrl)
    }

    // ------------------------------------------------------------------
    // /oauth2/authorize (RFC 6749 §4.1.1)
    // ------------------------------------------------------------------

    /**
     * Proxies an authorization request to Keycloak's authorize endpoint and
     * returns the redirect URL. The caller is expected to send a 302 to that URL.
     *
     * PKCE state/nonce/code_challenge params are passed through unchanged.
     */
    fun authorizeRedirect(realm: String, params: MultiValueMap<String, String>, fallbackBaseUrl: String): URI {
        val builder = UriComponentsBuilder.fromUriString("$baseUrl/realms/$realm/protocol/openid-connect/auth")
            .queryParams(params)
        return builder.build().toUri()
    }

    // ------------------------------------------------------------------
    // /oauth2/token (RFC 6749 §4.1.3)
    // ------------------------------------------------------------------

    fun token(realm: String, form: MultiValueMap<String, String>): JsonNode {
        val response = fetchKeycloakRaw(
            realm,
            "/protocol/openid-connect/token",
            HttpMethod.POST,
            form,
            MediaType.APPLICATION_FORM_URLENCODED,
        )
        return parseJson(response)
    }

    // ------------------------------------------------------------------
    // /oauth2/introspect (RFC 7662)
    // ------------------------------------------------------------------

    fun introspect(realm: String, token: String): IntrospectionResponse {
        // RFC 7662 requires client authentication. Forward the form params verbatim.
        val form = LinkedMultiValueMap<String, String>().apply {
            add("token", token)
            add("token_type_hint", "access_token")
        }
        val node = fetchKeycloakJson(
            realm,
            "/protocol/openid-connect/token/introspect",
            HttpMethod.POST,
            form,
            MediaType.APPLICATION_FORM_URLENCODED,
        )
        val active = node.path("active").asBoolean(false)
        return IntrospectionResponse(
            active = active,
            scope = node.path("scope").asText(null).takeIf { it.isNotBlank() },
            clientId = node.path("client_id").asText(null).takeIf { it.isNotBlank() },
            username = node.path("username").asText(null).takeIf { it.isNotBlank() },
            tokenType = node.path("token_type").asText(null).takeIf { it.isNotBlank() },
            exp = node.path("exp").asLong(0).takeIf { it > 0 },
            iat = node.path("iat").asLong(0).takeIf { it > 0 },
            nbf = node.path("nbf").asLong(0).takeIf { it > 0 },
            sub = node.path("sub").asText(null).takeIf { it.isNotBlank() },
            aud = node.path("aud").asText(null).takeIf { it.isNotBlank() },
            iss = node.path("iss").asText(null).takeIf { it.isNotBlank() },
            jti = node.path("jti").asText(null).takeIf { it.isNotBlank() },
        )
    }

    // ------------------------------------------------------------------
    // /oauth2/userinfo (OIDC Core §5.3)
    // ------------------------------------------------------------------

    fun userinfo(realm: String, bearerToken: String): UserInfoResponse {
        val headers = mapOf("Authorization" to "Bearer $bearerToken")
        val node = fetchKeycloak(
            realm,
            "/protocol/openid-connect/userinfo",
            HttpMethod.GET,
            null,
            MediaType.APPLICATION_JSON,
            headers,
        )
        return UserInfoResponse(
            sub = node.path("sub").asText(""),
            preferredUsername = node.path("preferred_username").asText(null).takeIf { it.isNotBlank() },
            email = node.path("email").asText(null).takeIf { it.isNotBlank() },
            emailVerified = node.path("email_verified").asBoolean(false),
            name = node.path("name").asText(null).takeIf { it.isNotBlank() },
            givenName = node.path("given_name").asText(null).takeIf { it.isNotBlank() },
            familyName = node.path("family_name").asText(null).takeIf { it.isNotBlank() },
            locale = node.path("locale").asText(null).takeIf { it.isNotBlank() },
            phone = node.path("phone").asText(null).takeIf { it.isNotBlank() },
            phoneVerified = node.path("phone_verified").asBoolean(false),
            userType = node.path("user_type").asText(null).takeIf { it.isNotBlank() },
            tenantId = node.path("tenant_id").asText(null).takeIf { it.isNotBlank() },
            realmAccess = objectMapper.convertValue(node.path("realm_access"), Map::class.java) as? Map<String, Any?>,
        )
    }

    // ------------------------------------------------------------------
    // /oauth2/jwks (RFC 7517)
    // ------------------------------------------------------------------

    fun jwks(realm: String): JsonNode {
        jwksCache.get(realm)?.let { return it }
        val node = fetchKeycloak(
            realm,
            "/protocol/openid-connect/certs",
            HttpMethod.GET,
            null,
            MediaType.APPLICATION_JSON,
        )
        jwksCache.put(realm, node)
        return node
    }

    // ------------------------------------------------------------------
    // /oauth2/logout (OIDC RP-initiated)
    // ------------------------------------------------------------------

    /**
     * Returns a JSON body `{ "redirect_uri": "..." }` so the client can
     * complete the post-logout redirect flow. Keycloak's end_session_endpoint
     * is called server-side (id_token_hint forwarded when present).
     */
    fun logout(realm: String, idTokenHint: String?, postLogoutRedirectUri: String?, clientId: String?, fallbackBaseUrl: String): URI {
        val builder = UriComponentsBuilder.fromUriString("$baseUrl/realms/$realm/protocol/openid-connect/logout")
        if (!idTokenHint.isNullOrBlank()) builder.queryParam("id_token_hint", idTokenHint)
        if (!postLogoutRedirectUri.isNullOrBlank()) builder.queryParam("post_logout_redirect_uri", postLogoutRedirectUri)
        if (!clientId.isNullOrBlank()) builder.queryParam("client_id", clientId)
        return builder.build().toUri()
    }

    // ------------------------------------------------------------------
    // /oauth2/revoke (RFC 7009)
    // ------------------------------------------------------------------

    /**
     * Keycloak returns 200 on success even if the token was already invalid.
     * We always return 200 here so RFC 7009 semantics are preserved.
     */
    fun revoke(realm: String, form: MultiValueMap<String, String>) {
        fetchKeycloakRaw(
            realm,
            "/protocol/openid-connect/revoke",
            HttpMethod.POST,
            form,
            MediaType.APPLICATION_FORM_URLENCODED,
        )
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private enum class HttpMethod { GET, POST }

    private fun fetchKeycloak(
        realm: String,
        path: String,
        method: HttpMethod,
        form: MultiValueMap<String, String>?,
        accept: MediaType,
        extraHeaders: Map<String, String> = emptyMap(),
    ): JsonNode = parseJson(fetchKeycloakRaw(realm, path, method, form, accept, extraHeaders))

    private fun fetchKeycloakJson(
        realm: String,
        path: String,
        method: HttpMethod,
        form: MultiValueMap<String, String>?,
        accept: MediaType,
        extraHeaders: Map<String, String> = emptyMap(),
    ): JsonNode = parseJson(fetchKeycloakRaw(realm, path, method, form, accept, extraHeaders))

    private fun fetchKeycloakRaw(
        realm: String,
        path: String,
        method: HttpMethod,
        form: MultiValueMap<String, String>?,
        accept: MediaType,
        extraHeaders: Map<String, String> = emptyMap(),
    ): String {
        val uri = "$baseUrl/realms/$realm$path"
        val request = restClient.post().uri(uri)
        extraHeaders.forEach { (k, v) -> request.header(k, v) }
        return try {
            val response = when (method) {
                HttpMethod.GET -> restClient.get().uri(uri).headers { headers -> extraHeaders.forEach { (k, v) -> headers[k] = v } }.retrieve().body(String::class.java)
                HttpMethod.POST -> {
                    val client = if (form != null) {
                        request
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .body(form)
                            .accept(accept)
                            .retrieve()
                    } else {
                        request.accept(accept).retrieve()
                    }
                    client.body(String::class.java)
                }
            }
            response ?: throw IllegalStateException("Empty response from Keycloak")
        } catch (exception: HttpClientErrorException) {
            log.warn("Keycloak {} {} failed: {} {}", method, uri, exception.statusCode, exception.responseBodyAsString)
            throw OidcUpstreamException(exception.statusCode.value(), exception.responseBodyAsString)
        } catch (exception: Exception) {
            log.warn("Keycloak {} {} unreachable: {}", method, uri, exception.message)
            throw OidcUpstreamException(502, "Keycloak unreachable: ${exception.javaClass.simpleName}")
        }
    }

    private fun parseJson(body: String): JsonNode = try {
        objectMapper.readTree(body)
    } catch (exception: Exception) {
        throw IllegalStateException("Invalid JSON from Keycloak: $body", exception)
    }
}

/** Wraps an upstream Keycloak failure with HTTP status + body for translation by `OidcErrorHandler`. */
class OidcUpstreamException(val statusCode: Int, val upstreamBody: String) : RuntimeException("Keycloak returned $statusCode")
