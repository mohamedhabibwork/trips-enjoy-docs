package com.trips_enjoy.notification.integration.keycloak

import com.trips_enjoy.notification.api.ApiException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Minimal Keycloak admin client for notification-service per
 * docs/services/notification-service/INTEGRATION.md §"Outbound APIs" +
 * TECH.md §10.
 *
 *  - Fetches user profile (phone, email, locale) for the recipient so the
 *    send orchestrator can pick a channel when the user has no on-device
 *    push token.
 *  - Wraps every call in an `ApiException(BAD_GATEWAY, DEPENDENCY_UPSTREAM_FAILURE, …)`
 *    so the caller's exception handler can map it to a 5xx envelope.
 *
 * For this slice the client is a thin contract; the HTTP client is
 * intentionally not wired in to avoid pulling additional Spring transitive
 * dependencies. The shape mirrors Keycloak's
 * `/admin/realms/{realm}/users/{id}` resource.
 */
interface KeycloakAdminClient {
	fun fetchUserProfile(userId: UUID): UserProfile
	fun fetchCustomerContact(userId: UUID): UserContact?
}

data class UserProfile(
	val userId: UUID,
	val username: String?,
	val email: String?,
	val phoneE164: String?,
	val locale: String,
	val preferredLocale: String?,
)

data class UserContact(
	val phoneE164: String?,
	val email: String?,
	val pushTokens: List<String>,
	val preferredChannel: String?,
)

@Component
class KeycloakAdminClientImpl(
	@Value("\${notification-service.keycloak.base-url}") private val baseUrl: String,
	@Value("\${notification-service.keycloak.admin-realm}") private val adminRealm: String,
	@Value("\${notification-service.keycloak.issuer-uri}") private val issuerUri: String,
) : KeycloakAdminClient {
	private val log = LoggerFactory.getLogger(javaClass)

	override fun fetchUserProfile(userId: UUID): UserProfile {
		// Real impl (deferred to wiring step): GET {baseUrl}/admin/realms/{adminRealm}/users/{userId}
		log.debug("keycloak.fetchUserProfile user={}", userId)
		throw ApiException(
			HttpStatus.BAD_GATEWAY,
			"DEPENDENCY_UPSTREAM_FAILURE",
			"Keycloak admin call not wired in slice",
		)
	}

	override fun fetchCustomerContact(userId: UUID): UserContact? {
		log.debug("keycloak.fetchCustomerContact user={}", userId)
		return null
	}
}