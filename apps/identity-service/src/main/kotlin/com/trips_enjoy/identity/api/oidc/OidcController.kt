package com.trips_enjoy.identity.api.oidc

import com.fasterxml.jackson.databind.JsonNode
import com.trips_enjoy.identity.application.oidc.OidcProxyService
import com.trips_enjoy.identity.api.oidc.IntrospectionResponse
import com.trips_enjoy.identity.api.oidc.UserInfoResponse
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.nio.charset.StandardCharsets

/**
 * OIDC Backend-for-Frontend endpoints for `identity-service`.
 *
 *  - `GET  /.well-known/openid-configuration`      — RFC 8414 / OIDC Discovery
 *  - `GET  /oauth2/authorize`                       — RFC 6749 §4.1.1 (redirect)
 *  - `POST /oauth2/token`                           — RFC 6749 §4.1.3 / §4.4
 *  - `POST /oauth2/introspect`                      — RFC 7662
 *  - `POST /oauth2/userinfo`                        — OIDC Core §5.3
 *  - `GET  /oauth2/jwks`                            — RFC 7517
 *  - `POST /oauth2/logout`                          — OIDC RP-initiated logout
 *  - `POST /oauth2/revoke`                          — RFC 7009
 *
 * All endpoints are `permitAll()` via `oidcFilterChain` in `SecurityConfiguration`.
 * Errors are formatted as RFC 6749 §5.2 `error`/`error_description` (see `OidcErrorHandler`).
 */
@RestController
@RequestMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
class OidcController(
    private val proxy: OidcProxyService,
    private val objectMapper: com.fasterxml.jackson.databind.ObjectMapper,
) {

    @GetMapping("/.well-known/openid-configuration")
    fun discovery(@RequestParam(value = "realm", required = false) realm: String?, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        val resolvedRealm = realm ?: defaultRealm()
        val base = requestBaseUrl(request)
        val body = proxy.discovery(resolvedRealm, base)
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "public, max-age=300")
            .body(objectMapper.convertValue(body, Map::class.java) as Map<String, Any?>)
    }

    @GetMapping("/oauth2/authorize")
    fun authorize(
        @RequestParam("realm") realm: String?,
        @RequestParam params: MultiValueMap<String, String>,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        val resolvedRealm = realm ?: defaultRealm()
        val redirect = proxy.authorizeRedirect(resolvedRealm, params, requestBaseUrl(request))
        return ResponseEntity.status(HttpStatus.FOUND)
            .location(redirect)
            .build()
    }

    @PostMapping(path = ["/oauth2/token"], consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun token(
        @RequestParam(value = "realm", required = false) realm: String?,
        @RequestParam form: MultiValueMap<String, String>,
    ): ResponseEntity<Map<String, Any?>> {
        val resolvedRealm = realm ?: defaultRealm()
        val body = proxy.token(resolvedRealm, form)
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header(HttpHeaders.PRAGMA, "no-cache")
            .body(objectMapper.convertValue(body, Map::class.java) as Map<String, Any?>)
    }

    @PostMapping(path = ["/oauth2/introspect"], consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun introspect(
        @RequestParam(value = "realm", required = false) realm: String?,
        @RequestParam form: MultiValueMap<String, String>,
    ): ResponseEntity<com.trips_enjoy.identity.api.oidc.IntrospectionResponse> {
        val resolvedRealm = realm ?: defaultRealm()
        val token = form.getFirst("token") ?: throw OidcClientException("invalid_request", "missing required parameter 'token'")
        val result = proxy.introspect(resolvedRealm, token)
        return ResponseEntity.ok(result)
    }

    @PostMapping(path = ["/oauth2/userinfo"], consumes = [MediaType.ALL_VALUE])
    fun userinfo(
        @RequestParam(value = "realm", required = false) realm: String?,
        @RequestHeader(HttpHeaders.AUTHORIZATION) authorization: String?,
    ): ResponseEntity<com.trips_enjoy.identity.api.oidc.UserInfoResponse> {
        val resolvedRealm = realm ?: defaultRealm()
        val token = authorization?.removePrefix("Bearer ")?.trim()
            ?: throw OidcClientException("invalid_request", "Bearer token required")
        val body = proxy.userinfo(resolvedRealm, token)
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(body)
    }

    @GetMapping("/oauth2/jwks")
    fun jwks(@RequestParam(value = "realm", required = false) realm: String?): ResponseEntity<Map<String, Any?>> {
        val resolvedRealm = realm ?: defaultRealm()
        val body = proxy.jwks(resolvedRealm)
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "public, max-age=300")
            .body(objectMapper.convertValue(body, Map::class.java) as Map<String, Any?>)
    }

    @PostMapping(path = ["/oauth2/logout"], consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun logout(
        @RequestParam(value = "realm", required = false) realm: String?,
        @RequestParam form: MultiValueMap<String, String>,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        val resolvedRealm = realm ?: defaultRealm()
        val redirect = proxy.logout(
            realm = resolvedRealm,
            idTokenHint = form.getFirst("id_token_hint"),
            postLogoutRedirectUri = form.getFirst("post_logout_redirect_uri"),
            clientId = form.getFirst("client_id"),
            fallbackBaseUrl = requestBaseUrl(request),
        )
        // RFC 7009 / OIDC RP-initiated logout: 302 redirect to Keycloak's end_session_endpoint.
        return ResponseEntity.status(HttpStatus.FOUND)
            .location(redirect)
            .build()
    }

    @PostMapping(path = ["/oauth2/revoke"], consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun revoke(
        @RequestParam(value = "realm", required = false) realm: String?,
        @RequestParam form: MultiValueMap<String, String>,
    ): ResponseEntity<Void> {
        val resolvedRealm = realm ?: defaultRealm()
        proxy.revoke(resolvedRealm, form)
        return ResponseEntity.ok().build()
    }

    private fun defaultRealm(): String = "platform-services"

    private fun requestBaseUrl(request: HttpServletRequest): String {
        val proto = request.getHeader("X-Forwarded-Proto") ?: request.scheme
        val host = request.getHeader("X-Forwarded-Host") ?: request.serverName
        val port = request.getHeader("X-Forwarded-Port")?.toIntOrNull() ?: request.serverPort
        val portPart = if (port == 80 || port == 443 || port < 0) "" else ":$port"
        return "$proto://$host$portPart"
    }
}

/**
 * Thrown by OIDC controller methods when a client request is malformed.
 * Translated to RFC 6749 §5.2 `error`/`error_description` by `OidcErrorHandler`.
 */
class OidcClientException(
    val error: String,
    val errorDescription: String,
) : RuntimeException("$error: $errorDescription")
