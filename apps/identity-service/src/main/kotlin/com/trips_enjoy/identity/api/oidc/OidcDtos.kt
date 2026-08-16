package com.trips_enjoy.identity.api.oidc

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * RFC 7662 OAuth 2.0 Token Introspection response.
 * `active` is REQUIRED; everything else is omitted when null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class IntrospectionResponse(
    val active: Boolean,
    @JsonProperty("scope") val scope: String? = null,
    @JsonProperty("client_id") val clientId: String? = null,
    val username: String? = null,
    @JsonProperty("token_type") val tokenType: String? = null,
    val exp: Long? = null,
    val iat: Long? = null,
    val nbf: Long? = null,
    val sub: String? = null,
    val aud: String? = null,
    val iss: String? = null,
    @JsonProperty("jti") val jti: String? = null,
)

/**
 * RFC 7009 Token Revocation request — accepts any token-type. Keycloak returns
 * 200 on success (even if the token was already invalid).
 */
data class RevocationRequest(
    val token: String,
    @JsonProperty("token_type_hint") val tokenTypeHint: String? = null,
)

/** RFC 6749 §5.2 error envelope returned from the OIDC token endpoints. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class OidcErrorEnvelope(
    val error: String,
    @JsonProperty("error_description") val errorDescription: String? = null,
    @JsonProperty("error_uri") val errorUri: String? = null,
)

/** `/oauth2/userinfo` response — the user's claims merged from the JWT. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class UserInfoResponse(
    val sub: String,
    @JsonProperty("preferred_username") val preferredUsername: String? = null,
    val email: String? = null,
    @JsonProperty("email_verified") val emailVerified: Boolean? = null,
    val name: String? = null,
    @JsonProperty("given_name") val givenName: String? = null,
    @JsonProperty("family_name") val familyName: String? = null,
    val locale: String? = null,
    val phone: String? = null,
    @JsonProperty("phone_verified") val phoneVerified: Boolean? = null,
    @JsonProperty("user_type") val userType: String? = null,
    @JsonProperty("tenant_id") val tenantId: String? = null,
    @JsonProperty("realm_access") val realmAccess: Map<String, Any?>? = null,
)
