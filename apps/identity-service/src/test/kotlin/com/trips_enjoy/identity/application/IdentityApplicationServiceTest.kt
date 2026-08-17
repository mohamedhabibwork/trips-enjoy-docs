package com.trips_enjoy.identity.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.trips_enjoy.identity.api.CreateIdentityRequest
import com.trips_enjoy.identity.api.DisableRequest
import com.trips_enjoy.identity.api.EraseRequest
import com.trips_enjoy.identity.api.ReinstateRequest
import com.trips_enjoy.identity.api.SuspensionRequest
import com.trips_enjoy.identity.api.UpdateIdentityRequest
import com.trips_enjoy.identity.domain.Identity
import com.trips_enjoy.identity.domain.IdentityAuditLog
import com.trips_enjoy.identity.domain.IdentityAuditLogRepository
import com.trips_enjoy.identity.domain.IdentityClaimHistoryRepository
import com.trips_enjoy.identity.domain.IdentityClaimsRepository
import com.trips_enjoy.identity.domain.IdentityRepository
import com.trips_enjoy.identity.domain.IdentityStatus
import com.trips_enjoy.identity.domain.IdempotencyRecord
import com.trips_enjoy.identity.domain.IdempotencyRecordRepository
import com.trips_enjoy.identity.domain.OutboxEvent
import com.trips_enjoy.identity.domain.OutboxEventRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.`when`
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.jwt.JwtDecoder
import java.time.Instant
import java.util.Optional
import java.util.UUID

class IdentityApplicationServiceTest {

    private val identities = mock(IdentityRepository::class.java)
    private val audits = mock(IdentityAuditLogRepository::class.java)
    private val outbox = mock(OutboxEventRepository::class.java)
    private val keys = mock(IdempotencyRecordRepository::class.java)
    private val keycloak = mock(KeycloakAdminClient::class.java)
    private val claims = mock(IdentityClaimsRepository::class.java)
    private val claimHistory = mock(IdentityClaimHistoryRepository::class.java)
    private val redis = mock(StringRedisTemplate::class.java)
    private val jwtDecoder = mock(JwtDecoder::class.java)
    private val valueOps = mock(ValueOperations::class.java) as ValueOperations<String, String>

    private fun mapper() = ObjectMapper()
        .registerModule(JavaTimeModule())
        .registerModule(KotlinModule.Builder().build())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    private fun service() = IdentityApplicationService(
        identities,
        audits,
        outbox,
        keys,
        mapper(),
        keycloak,
        jwtDecoder,
        claims,
        claimHistory,
        redis,
        86_400L,
    )

    @Test
    fun `create persists an identity audit entry and outbox event atomically`() {
        `when`(identities.findByKeycloakSubjectAndRealmAndDeletedAtIsNull("kc-sub", "platform-customer")).thenReturn(null)
        `when`(identities.findByKeycloakSubjectAndRealm("kc-sub", "platform-customer")).thenReturn(null)
        `when`(identities.save(any(Identity::class.java))).thenAnswer { invocation ->
            val identity = invocation.arguments[0] as Identity
            identity.id = UUID.randomUUID()
            identity
        }
        val actor = UUID.randomUUID(); val key = UUID.randomUUID()
        `when`(keys.findByActorAndIdempotencyKey(actor, key)).thenReturn(null)
        val result = service().create(CreateIdentityRequest("kc-sub", "platform-customer", "customer"), actor, key)
        assertEquals("active", result.status)
        verify(identities).save(any(Identity::class.java))
        verify(audits).save(any(IdentityAuditLog::class.java))
        verify(outbox).save(any(OutboxEvent::class.java))
        verify(keys).save(any(IdempotencyRecord::class.java))
        verifyNoInteractions(keycloak)
    }

    @Test
    fun `create returns 409 when soft-deleted kc_sub exists`() {
        val tombstone = Identity(
            keycloakSubject = "kc-sub",
            realm = "platform-customer",
            userType = "customer",
        ).apply {
            id = UUID.randomUUID()
            deletedAt = Instant.now()
        }
        `when`(identities.findByKeycloakSubjectAndRealmAndDeletedAtIsNull("kc-sub", "platform-customer")).thenReturn(null)
        `when`(identities.findByKeycloakSubjectAndRealm("kc-sub", "platform-customer")).thenReturn(tombstone)
        val actor = UUID.randomUUID(); val key = UUID.randomUUID()
        `when`(keys.findByActorAndIdempotencyKey(actor, key)).thenReturn(null)
        val ex = assertThrows(com.trips_enjoy.identity.api.ApiException::class.java) {
            service().create(CreateIdentityRequest("kc-sub", "platform-customer", "customer"), actor, key)
        }
        assertEquals(HttpStatus.CONFLICT, ex.status)
    }

    @Test
    fun `suspend persists audit and outbox for identity_user_suspended_v1`() {
        val id = UUID.randomUUID()
        val actor = UUID.randomUUID()
        val key = UUID.randomUUID()
        val identity = sampleIdentity(id).apply { status = IdentityStatus.ACTIVE }
        `when`(identities.findById(id)).thenReturn(Optional.of(identity))
        `when`(keys.findByActorAndIdempotencyKey(actor, key)).thenReturn(null)

        val result = service().suspend(id, SuspensionRequest("fraud", null, 30), actor, key)
        assertEquals("suspended", result.status)
        verify(keycloak).setEnabled(identity.realm, identity.keycloakSubject, false)
        verify(audits).save(any(IdentityAuditLog::class.java))
        verify(outbox).save(any(OutboxEvent::class.java))
    }

    @Test
    fun `suspend on erased identity throws 409`() {
        val id = UUID.randomUUID(); val actor = UUID.randomUUID(); val key = UUID.randomUUID()
        val erased = sampleIdentity(id).apply { status = IdentityStatus.ERASED }
        `when`(identities.findById(id)).thenReturn(Optional.of(erased))
        `when`(keys.findByActorAndIdempotencyKey(actor, key)).thenReturn(null)
        val ex = assertThrows(com.trips_enjoy.identity.api.ApiException::class.java) {
            service().suspend(id, SuspensionRequest("fraud", null, null), actor, key)
        }
        assertEquals(HttpStatus.CONFLICT, ex.status)
    }

    @Test
    fun `reinstate on non-suspended throws 409`() {
        val id = UUID.randomUUID(); val actor = UUID.randomUUID(); val key = UUID.randomUUID()
        val active = sampleIdentity(id).apply { status = IdentityStatus.ACTIVE }
        `when`(identities.findById(id)).thenReturn(Optional.of(active))
        `when`(keys.findByActorAndIdempotencyKey(actor, key)).thenReturn(null)
        val ex = assertThrows(com.trips_enjoy.identity.api.ApiException::class.java) {
            service().reinstate(id, ReinstateRequest("note"), actor, key)
        }
        assertEquals(HttpStatus.CONFLICT, ex.status)
    }

    @Test
    fun `erase anonymizes PII and emits identity_user_erased_v1`() {
        val id = UUID.randomUUID(); val actor = UUID.randomUUID(); val key = UUID.randomUUID()
        val active = sampleIdentity(id).apply { status = IdentityStatus.ACTIVE; name = "Old Name"; email = "old@example.com" }
        `when`(identities.findById(id)).thenReturn(Optional.of(active))
        `when`(keys.findByActorAndIdempotencyKey(actor, key)).thenReturn(null)
        val response = service().erase(id, EraseRequest("user_request", "GDPR"), actor, key)
        assertEquals("erased", response.status)
        assertEquals(null, active.name)
        assertEquals(null, active.email)
        // Two outbox events: identity.user.erased.v1 (from change()) + identity.user.deleted.v1 (companion delete)
        verify(outbox, atLeastOnce()).save(any(OutboxEvent::class.java))
    }

    @Test
    fun `logout captures sessions_revoked and revoked_jtis from Keycloak and writes Redis denylist`() {
        val id = UUID.randomUUID(); val actor = UUID.randomUUID(); val key = UUID.randomUUID()
        val identity = sampleIdentity(id)
        `when`(identities.findById(id)).thenReturn(Optional.of(identity))
        `when`(keys.findByActorAndIdempotencyKey(actor, key)).thenReturn(null)
        `when`(keycloak.logout(identity.realm, identity.keycloakSubject)).thenReturn(
            LogoutResult(sessionsRevoked = 2, revokedJtis = listOf("jti-1", "jti-2")),
        )
        `when`(redis.opsForValue()).thenReturn(valueOps)
        val response = service().logout(
            id,
            com.trips_enjoy.identity.api.LogoutRequest("security", "session theft"),
            actor,
            key,
        )
        assertEquals(2, response.sessions_revoked)
        assertEquals(listOf("jti-1", "jti-2"), response.revoked_jtis)
        verify(valueOps).set(org.mockito.ArgumentMatchers.eq("identity.session.revoked.jti-1"), org.mockito.ArgumentMatchers.any<String>(), org.mockito.ArgumentMatchers.any<java.time.Duration>())
        verify(valueOps).set(org.mockito.ArgumentMatchers.eq("identity.session.revoked.jti-2"), org.mockito.ArgumentMatchers.any<String>(), org.mockito.ArgumentMatchers.any<java.time.Duration>())
    }

    @Test
    fun `update writes identity_claim_history rows for changed fields`() {
        val id = UUID.randomUUID(); val actor = UUID.randomUUID()
        val identity = sampleIdentity(id).apply { name = "Old"; locale = "en-US" }
        `when`(identities.findById(id)).thenReturn(Optional.of(identity))
        service().update(id, UpdateIdentityRequest(name = "New Name", locale = "nl-NL"), actor)
        verify(claimHistory, atLeastOnce()).save(any(com.trips_enjoy.identity.domain.IdentityClaimHistory::class.java))
        verify(claims).save(any(com.trips_enjoy.identity.domain.IdentityClaims::class.java))
    }

    @Test
    fun `idempotent suspend replays stored response when key and hash match`() {
        val id = UUID.randomUUID(); val actor = UUID.randomUUID(); val key = UUID.randomUUID()
        val identity = sampleIdentity(id).apply { status = IdentityStatus.ACTIVE }
        `when`(identities.findById(id)).thenReturn(Optional.of(identity))
        val req = SuspensionRequest("fraud", null, null)
        val mapper = mapper()
        // The stored response is the prior call's IdentityResponse JSON
        val priorResponse = mapper.writeValueAsString(
            com.trips_enjoy.identity.api.IdentityResponse(
                id = id,
                kc_sub = "kc-sub-$id",
                realm = "platform-customer",
                user_type = "customer",
                region = null, tenant_id = null,
                name = null, email = null, email_verified = false,
                phone = null, phone_verified = false,
                locale = null, mfa_enabled = false,
                status = "suspended",
                suspended_reason = "fraud",
                suspended_at = Instant.now(),
                erased_at = null,
                created_at = Instant.now(),
                updated_at = Instant.now(),
            ),
        )
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(mapper.writeValueAsBytes(req)).joinToString("") { "%02x".format(it) }
        val stored = IdempotencyRecord(
            id = UUID.randomUUID(),
            actor = actor,
            idempotencyKey = key,
            requestHash = hash,
            responseStatus = 200,
            responseBody = priorResponse,
            expiresAt = Instant.now().plusSeconds(60),
        )
        `when`(keys.findByActorAndIdempotencyKey(actor, key)).thenReturn(stored)
        val first = service().suspend(id, req, actor, key)
        verifyNoInteractions(keycloak)
        assertEquals("suspended", first.status)
    }

    private fun sampleIdentity(id: UUID) = Identity(
        keycloakSubject = "kc-sub-$id",
        realm = "platform-customer",
        userType = "customer",
    ).apply { this.id = id }
}
