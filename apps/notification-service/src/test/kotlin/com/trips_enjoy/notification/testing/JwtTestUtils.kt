package com.trips_enjoy.notification.testing

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Date
import java.util.UUID

/**
 * Test-only JWT helper — same shape as identity-service's `JwtTestUtils`.
 * Mints RS256-signed JWTs that the test-only `JwtDecoder` bean can verify
 * without needing a live Keycloak.
 */
class JwtTestUtils {

	val rsaKey: RSAKey = RSAKeyGenerator(2048).keyID("test-key").generate()
	val publicKey: RSAPublicKey = rsaKey.toRSAPublicKey()
	private val privateKey: RSAPrivateKey = rsaKey.toRSAPrivateKey()
	private val signer = RSASSASigner(privateKey)

	fun mintToken(
		subject: UUID = UUID.randomUUID(),
		username: String = "test-$subject",
		realm: String = "platform-services",
		roles: List<String> = emptyList(),
		scopes: List<String> = emptyList(),
		ttlSeconds: Long = 3600,
	): String {
		val now = Date()
		val claims = JWTClaimsSet.Builder()
			.subject(subject.toString())
			.issuer("http://localhost:8181/realms/$realm")
			.audience("notification-service")
			.jwtID(UUID.randomUUID().toString())
			.issueTime(now)
			.expirationTime(Date(now.time + ttlSeconds * 1000))
			.claim("preferred_username", username)
			.claim("scope", scopes.joinToString(" "))
			.claim("realm_access", mapOf("roles" to roles))
			.build()
		val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.keyID).build(), claims)
		jwt.sign(signer)
		return jwt.serialize()
	}

	fun mintAdminToken(role: String = "platform.admin"): String =
		mintToken(roles = listOf(role))

	fun mintServiceToken(): String =
		mintToken(roles = listOf("service"))
}