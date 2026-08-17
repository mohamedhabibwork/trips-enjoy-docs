package com.trips_enjoy.identity.testing

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Date
import java.util.UUID

/**
 * Minimal in-tree JWT minting helper. Stand-in for the platform's
 * `platform-spring-boot-test:TestKeycloakContainer + JwtTestUtils`.
 *
 * Mints Keycloak-shaped JWTs signed with an RSA keypair; the public key is
 * exposed so tests can wire a `NimbusJwtDecoder.withPublicKey(...)` bean.
 */
class JwtTestUtils {

    data class TestKey(val keyPair: KeyPair) {
        val publicKey: RSAPublicKey get() = keyPair.public as RSAPublicKey
        val privateKey: RSAPrivateKey get() = keyPair.private as RSAPrivateKey
        val kid: String = UUID.randomUUID().toString()
        val jwk: RSAKey = RSAKey.Builder(publicKey).keyID(kid).build()
    }

    private val signer: TestKey = run {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        TestKey(gen.generateKeyPair())
    }

    val publicKey: RSAPublicKey get() = signer.publicKey
    val privateKey: RSAPrivateKey get() = signer.privateKey
    val kid: String get() = signer.kid

    fun mintToken(
        subject: String = UUID.randomUUID().toString(),
        realm: String = "platform-services",
        username: String? = subject,
        roles: List<String> = listOf("customer"),
        scopes: List<String> = listOf("openid", "profile"),
        jti: String = UUID.randomUUID().toString(),
        ttlSeconds: Long = 300,
    ): String {
        val now = Instant.now()
        val claims = JWTClaimsSet.Builder()
            .issuer("http://test-keycloak:8181/realms/$realm")
            .subject(subject)
            .jwtID(jti)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(ttlSeconds)))
            .claim("preferred_username", username)
            .claim("scope", scopes.joinToString(" "))
            .claim("realm_access", mapOf("roles" to roles))
            .claim("tenant_id", null)
            .build()
        val signedJwt = SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).type(JOSEObjectType.JWT).build(),
            claims,
        )
        signedJwt.sign(RSASSASigner(privateKey))
        return signedJwt.serialize()
    }

    fun mintAdminToken(
        role: String = "platform.admin",
        subject: String = UUID.randomUUID().toString(),
        realm: String = "platform-internal",
        ttlSeconds: Long = 300,
    ): String = mintToken(subject = subject, realm = realm, roles = listOf(role), scopes = listOf("openid"), jti = UUID.randomUUID().toString(), ttlSeconds = ttlSeconds)
}
