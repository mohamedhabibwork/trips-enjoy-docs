package com.trips_enjoy.identity.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.identity.api.ApiException
import com.trips_enjoy.identity.api.CreateIdentityRequest
import com.trips_enjoy.identity.api.EraseRequest
import com.trips_enjoy.identity.api.ErasureResponse
import com.trips_enjoy.identity.api.IdentityResponse
import com.trips_enjoy.identity.api.IntrospectionResponse
import com.trips_enjoy.identity.api.LogoutRequest
import com.trips_enjoy.identity.api.LogoutResponse
import com.trips_enjoy.identity.api.ReinstateRequest
import com.trips_enjoy.identity.api.DisableRequest
import com.trips_enjoy.identity.api.SuspensionRequest
import com.trips_enjoy.identity.api.UpdateIdentityRequest
import com.trips_enjoy.identity.api.toResponse
import com.trips_enjoy.identity.domain.Identity
import com.trips_enjoy.identity.domain.IdentityAuditLog
import com.trips_enjoy.identity.domain.IdentityAuditLogRepository
import com.trips_enjoy.identity.domain.IdentityClaimHistory
import com.trips_enjoy.identity.domain.IdentityClaimHistoryRepository
import com.trips_enjoy.identity.domain.IdentityClaims
import com.trips_enjoy.identity.domain.IdentityClaimsRepository
import com.trips_enjoy.identity.domain.IdentityRepository
import com.trips_enjoy.identity.domain.IdentityStatus
import com.trips_enjoy.identity.domain.IdempotencyRecord
import com.trips_enjoy.identity.domain.IdempotencyRecordRepository
import com.trips_enjoy.identity.domain.OutboxEvent
import com.trips_enjoy.identity.domain.OutboxEventRepository
import com.trips_enjoy.identity.util.uuidV7
import org.springframework.beans.factory.annotation.Value
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class IdentityApplicationService(
    private val identities: IdentityRepository,
    private val auditLogs: IdentityAuditLogRepository,
    private val outbox: OutboxEventRepository,
    private val idempotency: IdempotencyRecordRepository,
    private val objectMapper: ObjectMapper,
    private val keycloak: KeycloakAdminClient,
    private val jwtDecoder: JwtDecoder,
    private val identityClaims: IdentityClaimsRepository,
    private val claimHistory: IdentityClaimHistoryRepository,
    private val redis: StringRedisTemplate,
    @Value("\${identity.session.denylist-ttl-seconds:86400}") private val denylistTtlSeconds: Long,
) {

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = ["identity-by-id"], key = "#id")
    fun get(id: UUID): IdentityResponse {
        val identity = identity(id)
        if (identity.status == IdentityStatus.ERASED) {
            throw ApiException(HttpStatus.GONE, "GONE", "Identity has been erased")
        }
        return identity.toResponse()
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = ["identity-by-subject"], key = "#realm + ':' + #subject")
    fun getBySubject(subject: String, realm: String): IdentityResponse =
        (identities.findByKeycloakSubjectAndRealmAndDeletedAtIsNull(subject, realm)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Identity not found")).toResponse()

    @Transactional(readOnly = true)
    fun introspect(token: String): IntrospectionResponse {
        val jwt = try { jwtDecoder.decode(token) } catch (_: Exception) { throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Token could not be verified") }
        val realm = jwt.issuer?.path?.substringAfterLast('/') ?: throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Token issuer is missing a realm")
        val identity = identities.findByKeycloakSubjectAndRealmAndDeletedAtIsNull(jwt.subject, realm)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Identity not found")
        // §8.8 Redis denylist: check for revoked jti
        val jti = jwt.id
        if (jti != null && redis.hasKey(denylistKey(jti))) {
            throw ApiException(HttpStatus.UNAUTHORIZED, "TOKEN_REVOKED", "Token has been revoked")
        }
        val realmAccess = jwt.getClaimAsMap("realm_access") ?: emptyMap<String, Any>()
        @Suppress("UNCHECKED_CAST") val roles = (realmAccess["roles"] as? Collection<String>)?.toList() ?: emptyList()
        val identityId = requireNotNull(identity.id) { "Identity.id must be assigned after save" }
        return IntrospectionResponse(
            identityId, identity.keycloakSubject, identity.realm, identity.userType, roles,
            jwt.getClaimAsStringList("scope") ?: emptyList(), identity.tenantId, identity.status,
            mapOf(
                "name" to identity.name,
                "email" to identity.email,
                "phone" to identity.phone,
                "locale" to identity.locale,
                "mfa_enabled" to identity.mfaEnabled,
            ),
        )
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = ["identity-claims"], key = "#id")
    fun getClaims(id: UUID): Map<String, Any?> {
        val identity = identity(id)
        val identityId = requireNotNull(identity.id) { "Identity.id must be assigned after save" }
        val claims = identityClaims.findById(id).orElseGet { IdentityClaims(identityId = identityId) }
        return mapOf(
            "identity_id" to identityId,
            "name" to claims.name,
            "email" to claims.email,
            "phone" to claims.phone,
            "locale" to claims.locale,
            "mfa_enabled" to identity.mfaEnabled,
            "mfa_methods" to claims.mfaMethods,
            "amr" to claims.amr,
            "last_refreshed_at" to claims.lastRefreshedAt,
        )
    }

    @Transactional(readOnly = true)
    fun listSessions(id: UUID): List<Map<String, Any?>> {
        val identity = identity(id)
        return keycloak.listSessions(identity.realm, identity.keycloakSubject).map { s ->
            mapOf(
                "jti" to s.id,
                "ip" to s.ipAddress,
                "started" to s.started,
                "last_access" to s.lastAccess,
            )
        }
    }

    @Transactional
    @CacheEvict(cacheNames = ["identity-by-id", "identity-by-subject", "identity-claims"], allEntries = true)
    fun create(request: CreateIdentityRequest, actor: UUID, idempotencyKey: UUID): IdentityResponse = idempotent(actor, idempotencyKey, request) {
        if (identities.findByKeycloakSubjectAndRealmAndDeletedAtIsNull(request.kc_sub, request.realm) != null) {
            throw ApiException(HttpStatus.CONFLICT, "CONFLICT", "An identity mapping already exists")
        }
        // SRS §9: soft-deleted kc_sub cannot be reused
        if (identities.findByKeycloakSubjectAndRealm(request.kc_sub, request.realm)?.deletedAt != null) {
            throw ApiException(HttpStatus.CONFLICT, "CONFLICT", "kc_sub has been erased and cannot be reused")
        }
        val now = Instant.now()
        val identity = Identity(
            keycloakSubject = request.kc_sub, realm = request.realm, userType = request.user_type,
            region = request.region, tenantId = request.tenant_id, name = request.name, email = request.email,
            phone = request.phone, locale = request.locale,
        )
        identities.save(identity)
        val identityId = requireNotNull(identity.id) { "Identity.id must be assigned after save" }
        change(
            identity = identity,
            action = "create",
            actor = actor,
            reason = null,
            eventName = "identity.user.created.v1",
            payload = createdPayload(identity, now),
            auditExtras = null,
        )
        // refresh cached claims too
        upsertClaims(identity, name = request.name, email = request.email, phone = request.phone, locale = request.locale)
        identity.toResponse()
    }

    @Transactional
    @CacheEvict(cacheNames = ["identity-by-id", "identity-by-subject", "identity-claims"], allEntries = true)
    fun update(id: UUID, request: UpdateIdentityRequest, actor: UUID): IdentityResponse {
        val identity = identity(id)
        val identityId = requireNotNull(identity.id) { "Identity.id must be assigned after save" }
        if (request.row_version != null && request.row_version != identity.version) {
            throw ApiException(HttpStatus.CONFLICT, "CONFLICT", "Identity was modified by another request")
        }
        val before = mapOf(
            "name" to identity.name, "email" to identity.email, "phone" to identity.phone,
            "locale" to identity.locale, "email_verified" to identity.emailVerified,
            "phone_verified" to identity.phoneVerified, "mfa_enabled" to identity.mfaEnabled,
        )
        val changed = mutableMapOf<String, Any?>()
        request.name?.let { identity.name = it; changed["name"] = it }
        request.email?.let { identity.email = it; changed["email"] = it }
        request.phone?.let { identity.phone = it; changed["phone"] = it }
        request.locale?.let { identity.locale = it; changed["locale"] = it }
        request.email_verified?.let { identity.emailVerified = it; changed["email_verified"] = it }
        request.phone_verified?.let { identity.phoneVerified = it; changed["phone_verified"] = it }
        request.mfa_enabled?.let { identity.mfaEnabled = it; changed["mfa_enabled"] = it }

        // append-only claim-history rows (one per changed field)
        changed.forEach { (field, newValue) ->
            claimHistory.save(
                IdentityClaimHistory(
                    id = uuidV7(),
                    identityId = identityId,
                    field = field,
                    oldValue = objectMapper.writeValueAsString(before[field]),
                    newValue = objectMapper.writeValueAsString(newValue),
                    source = "service",
                    changedBy = actor,
                ),
            )
        }
        upsertClaims(identity, name = identity.name, email = identity.email, phone = identity.phone, locale = identity.locale)
        change(
            identity = identity,
            action = "update",
            actor = actor,
            reason = null,
            eventName = "identity.user.updated.v1",
            payload = mapOf("identity_id" to identityId, "changed_fields" to changed.keys, "values" to changed, "occurred_at" to Instant.now()),
            auditExtras = null,
        )
        return identity.toResponse()
    }

    @Transactional
    @CacheEvict(cacheNames = ["identity-by-id", "identity-by-subject", "identity-claims"], allEntries = true)
    fun suspend(id: UUID, request: SuspensionRequest, actor: UUID, key: UUID): IdentityResponse = idempotent(actor, key, request) {
        val identity = identity(id)
        val identityId = requireNotNull(identity.id) { "Identity.id must be assigned after save" }
        if (identity.status == IdentityStatus.ERASED || identity.status == IdentityStatus.DISABLED) {
            throw ApiException(HttpStatus.CONFLICT, "CONFLICT", "Identity cannot be suspended")
        }
        if (identity.status == IdentityStatus.SUSPENDED && identity.suspendedReason != request.reason) {
            throw ApiException(HttpStatus.CONFLICT, "CONFLICT", "Identity is already suspended for a different reason")
        }
        keycloak.setEnabled(identity.realm, identity.keycloakSubject, false)
        val now = Instant.now()
        identity.status = IdentityStatus.SUSPENDED
        identity.suspendedReason = request.reason
        identity.suspendedAt = now
        identity.suspendedBy = actor
        change(
            identity = identity,
            action = "suspend",
            actor = actor,
            reason = request.reason,
            eventName = "identity.user.suspended.v1",
            payload = mapOf(
                "identity_id" to identityId,
                "reason" to request.reason,
                "expected_duration_days" to request.expected_duration_days,
                "suspended_by" to actor,
                "occurred_at" to now,
            ),
            auditExtras = null,
        )
        identity.toResponse()
    }

    @Transactional
    @CacheEvict(cacheNames = ["identity-by-id", "identity-by-subject", "identity-claims"], allEntries = true)
    fun disable(id: UUID, request: DisableRequest, actor: UUID, key: UUID): IdentityResponse = idempotent(actor, key, request) {
        val identity = identity(id)
        val identityId = requireNotNull(identity.id) { "Identity.id must be assigned after save" }
        if (identity.status == IdentityStatus.ERASED || identity.status == IdentityStatus.DISABLED) {
            throw ApiException(HttpStatus.CONFLICT, "CONFLICT", "Identity is already disabled or erased")
        }
        keycloak.setEnabled(identity.realm, identity.keycloakSubject, false)
        val now = Instant.now()
        identity.status = IdentityStatus.DISABLED
        identity.disabledAt = now
        identity.disabledBy = actor
        change(
            identity = identity,
            action = "disable",
            actor = actor,
            reason = request.reason,
            eventName = "identity.user.disabled.v1",
            payload = mapOf(
                "identity_id" to identityId,
                "reason" to request.reason,
                "disabled_by" to actor,
                "occurred_at" to now,
            ),
            auditExtras = null,
        )
        identity.toResponse()
    }

    @Transactional
    @CacheEvict(cacheNames = ["identity-by-id", "identity-by-subject", "identity-claims"], allEntries = true)
    fun reinstate(id: UUID, request: ReinstateRequest, actor: UUID, key: UUID): IdentityResponse = idempotent(actor, key, request) {
        val identity = identity(id)
        val identityId = requireNotNull(identity.id) { "Identity.id must be assigned after save" }
        if (identity.status != IdentityStatus.SUSPENDED) {
            throw ApiException(HttpStatus.CONFLICT, "CONFLICT", "Only suspended identities can be reinstated")
        }
        keycloak.setEnabled(identity.realm, identity.keycloakSubject, true)
        val now = Instant.now()
        identity.status = IdentityStatus.ACTIVE
        identity.suspendedReason = null
        identity.suspendedAt = null
        identity.suspendedBy = null
        change(
            identity = identity,
            action = "reinstate",
            actor = actor,
            reason = request.note,
            eventName = "identity.user.reinstated.v1",
            payload = mapOf(
                "identity_id" to identityId,
                "reinstated_by" to actor,
                "occurred_at" to now,
            ),
            auditExtras = null,
        )
        identity.toResponse()
    }

    @Transactional
    @CacheEvict(cacheNames = ["identity-by-id", "identity-by-subject", "identity-claims"], allEntries = true)
    fun erase(id: UUID, request: EraseRequest, actor: UUID, key: UUID): ErasureResponse = idempotent(actor, key, request) {
        val identity = identity(id)
        val identityId = requireNotNull(identity.id) { "Identity.id must be assigned after save" }
        if (identity.status == IdentityStatus.ERASED) {
            throw ApiException(HttpStatus.CONFLICT, "CONFLICT", "Identity is already erased")
        }
        keycloak.setEnabled(identity.realm, identity.keycloakSubject, false)
        // INTEGRATION.md §2 row 5: erase at Keycloak is a full DELETE, not just disable.
        keycloak.deleteUser(identity.realm, identity.keycloakSubject)
        val now = Instant.now()
        identity.status = IdentityStatus.ERASED
        identity.name = null; identity.email = null; identity.phone = null; identity.locale = null
        identity.erasedAt = now
        identity.erasedBy = actor
        identity.deletedAt = now
        // redact cached claims
        identityClaims.findById(id).ifPresent { existing ->
            identityClaims.save(
                existing.copy(
                    name = null, email = null, phone = null, locale = null,
                    updatedAt = now, rowVersion = existing.rowVersion + 1,
                ),
            )
        }
        change(
            identity = identity,
            action = "erase",
            actor = actor,
            reason = request.legal_basis,
            eventName = "identity.user.erased.v1",
            payload = mapOf(
                "identity_id" to identityId,
                "legal_basis" to request.legal_basis,
                "erased_by" to actor,
                "occurred_at" to now,
            ),
            auditExtras = null,
        )
        // Companion delete event — distinct topic so downstream consumers can
        // react to "Keycloak user removed" separately from "PII redacted".
        outbox.save(
            OutboxEvent(
                id = uuidV7(),
                aggregateType = "Identity",
                aggregateId = identityId,
                topic = "identity.user.deleted",
                eventName = "identity.user.deleted.v1",
                payload = objectMapper.writeValueAsString(
                    mapOf(
                        "event_id" to uuidV7().toString(),
                        "event_name" to "identity.user.deleted.v1",
                        "aggregate_id" to identityId.toString(),
                        "occurred_at" to now.toString(),
                        "data" to mapOf(
                            "identity_id" to identityId,
                            "legal_basis" to request.legal_basis,
                            "erased_by" to actor,
                            "occurred_at" to now,
                        ),
                    ),
                ),
            ),
        )
        ErasureResponse(identityId, "erased", now)
    }

    @Transactional
    @CacheEvict(cacheNames = ["identity-by-id", "identity-by-subject"], allEntries = true)
    fun logout(id: UUID, request: LogoutRequest, actor: UUID, key: UUID): LogoutResponse = idempotent(actor, key, request) {
        val identity = identity(id)
        val identityId = requireNotNull(identity.id) { "Identity.id must be assigned after save" }
        val result = keycloak.logout(identity.realm, identity.keycloakSubject)
        // populate Redis denylist per §8.8
        result.revokedJtis.forEach { jti ->
            redis.opsForValue().set(denylistKey(jti), identityId.toString(), java.time.Duration.ofSeconds(denylistTtlSeconds))
        }
        // emit one identity.session.revoked.v1 per jti (per §3.7)
        val now = Instant.now()
        result.revokedJtis.forEach { jti ->
            val payload = objectMapper.writeValueAsString(
                mapOf(
                    "identity_id" to identityId,
                    "jti" to jti,
                    "exp" to 0L,
                    "reason" to request.reason,
                    "revoked_by" to actor,
                    "occurred_at" to now,
                ),
            )
            outbox.save(
                OutboxEvent(
                    id = uuidV7(),
                    aggregateType = "Identity",
                    aggregateId = identityId,
                    topic = "identity.session.revoked",
                    eventName = "identity.session.revoked.v1",
                    payload = payload,
                ),
            )
        }
        auditLogs.save(IdentityAuditLog(uuidV7(), identityId, "force_logout", actor, "service", request.reason))
        LogoutResponse(id, result.sessionsRevoked, result.revokedJtis)
    }

    private fun identity(id: UUID) = identities.findById(id).orElseThrow { ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Identity not found") }

    private fun createdPayload(identity: Identity, occurredAt: Instant) = mapOf(
        "identity_id" to identity.id,
        "kc_sub" to identity.keycloakSubject,
        "realm" to identity.realm,
        "user_type" to identity.userType,
        "region" to identity.region,
        "tenant_id" to identity.tenantId,
        "email_verified" to identity.emailVerified,
        "phone_verified" to identity.phoneVerified,
        "mfa_enabled" to identity.mfaEnabled,
        "occurred_at" to occurredAt,
    )

    private fun change(
        identity: Identity,
        action: String,
        actor: UUID,
        reason: String?,
        eventName: String,
        payload: Map<String, Any?>,
        auditExtras: IdentityAuditLogExtras?,
    ) {
        val identityId = requireNotNull(identity.id) { "Identity.id must be assigned after save" }
        auditLogs.save(
            IdentityAuditLog(
                id = uuidV7(),
                identityId = identityId,
                action = action,
                actor = actor,
                actorType = "service",
                reason = reason,
                occurredByRole = auditExtras?.occurredByRole,
                breakGlass = auditExtras?.breakGlass ?: false,
                cosigner = auditExtras?.cosigner,
                signature = auditExtras?.signature,
                preset = auditExtras?.preset,
                role = auditExtras?.role,
            ),
        )
        val envelope = objectMapper.writeValueAsString(
            mapOf(
                "event_id" to uuidV7(),
                "event_name" to eventName,
                "aggregate_id" to identityId,
                "occurred_at" to Instant.now().toString(),
                "data" to payload,
            ),
        )
        outbox.save(
            OutboxEvent(
                id = uuidV7(),
                aggregateType = "Identity",
                aggregateId = identityId,
                topic = eventName.substringBeforeLast(".v"),
                eventName = eventName,
                payload = envelope,
            ),
        )
    }

    private fun upsertClaims(identity: Identity, name: String?, email: String?, phone: String?, locale: String?) {
        val now = Instant.now()
        val identityId = requireNotNull(identity.id) { "Identity.id must be assigned after save" }
        val existing = identityClaims.findById(identityId).orElse(null)
        if (existing == null) {
            identityClaims.save(IdentityClaims(identityId = identityId, name = name, email = email, phone = phone, locale = locale, lastRefreshedAt = now, createdAt = now, updatedAt = now))
        } else {
            identityClaims.save(
                existing.copy(
                    name = name, email = email, phone = phone, locale = locale,
                    lastRefreshedAt = now, updatedAt = now, rowVersion = existing.rowVersion + 1,
                ),
            )
        }
    }

    private fun denylistKey(jti: String) = "identity.session.revoked.$jti"

    private inline fun <reified T : Any> idempotent(actor: UUID, key: UUID, request: Any, action: () -> T): T {
        val hash = MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(request)).joinToString("") { "%02x".format(it) }
        val existing = idempotency.findByActorAndIdempotencyKey(actor, key)
        if (existing != null) {
            if (existing.requestHash != hash) throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED", "Idempotency key was used with a different request")
            return objectMapper.readValue(existing.responseBody, T::class.java)
        }
        val response = action()
        idempotency.save(IdempotencyRecord(uuidV7(), actor, key, hash, 200, objectMapper.writeValueAsString(response), Instant.now().plus(24, ChronoUnit.HOURS)))
        return response
    }
}

/** Optional extras for audit_log rows that record admin role-grant/revoke. */
data class IdentityAuditLogExtras(
    val role: String? = null,
    val preset: String? = null,
    val cosigner: UUID? = null,
    val breakGlass: Boolean = false,
    val signature: String? = null,
    val occurredByRole: String? = null,
)
