package com.trips_enjoy.identity.application

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.identity.api.ApiException
import com.trips_enjoy.identity.domain.Identity
import com.trips_enjoy.identity.domain.IdentityRepository
import com.trips_enjoy.identity.domain.OutboxEvent
import com.trips_enjoy.identity.domain.OutboxEventRepository
import com.trips_enjoy.identity.domain.RoleAssignmentHistory
import com.trips_enjoy.identity.domain.RoleAssignmentHistoryRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Implements the `/admin/v1/identities/{id}/roles` endpoints per
 * INTEGRATION §1.11–§1.13 and emits the `identity.role.granted.v1` /
 * `identity.role.revoked.v1` events per §3.8/§3.9.
 *
 * Super-admin grant/revoke gates (cosigner, signature, off-hours) are
 * enforced at the controller layer via dedicated aspects.
 */
@Service
class RoleAssignmentService(
    private val identities: IdentityRepository,
    private val keycloak: KeycloakAdminClient,
    private val history: RoleAssignmentHistoryRepository,
    private val outbox: OutboxEventRepository,
    private val objectMapper: ObjectMapper,
) {
    companion object {
        /** Per `shared/TIME_BOUNDED_ALIASES.md` / admin-service canonical preset. */
        val SUPER_ADMIN_PRESET_ROLES = setOf("platform.super_admin", "identity.admin")

        /** RFC-0034 (TECH §10.5): off-hours window for forced co-signature (UTC). */
        val OFF_HOURS_START_UTC = 0
        val OFF_HOURS_END_UTC = 6

        /** Roles whose grant requires the super-admin gates. */
        val SUPER_ADMIN_ROLES = setOf("platform.super_admin")
    }

    @Transactional(readOnly = true)
    fun listRoles(id: UUID): RoleListResponse {
        val identity = identity(id)
        val identityId = requireNotNull(identity.id) { "Identity.id must be assigned after save" }
        val roles = keycloak.listRealmRoles(identity.realm, identity.keycloakSubject)
        val presets = presetsMatching(roles)
        return RoleListResponse(
            identityId = identityId,
            kcSub = identity.keycloakSubject,
            realm = identity.realm,
            roles = roles,
            presets = presets,
            evaluatedAt = Instant.now(),
        )
    }

    @Transactional
    fun grant(
        identity: Identity,
        role: String,
        actor: UUID,
        cosigner: UUID?,
        breakGlass: Boolean,
        signature: String?,
        preset: String?,
        reasonCode: String?,
        endpoint: String,
    ): RoleListResponse {
        if (role !in allowedGrantRoles()) throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Role is not grantable by this service")
        val current = keycloak.listRealmRoles(identity.realm, identity.keycloakSubject)
        if (role in current) throw ApiException(HttpStatus.CONFLICT, "ROLE_ALREADY_ASSIGNED", "Role is already assigned")
        keycloak.grantRealmRole(identity.realm, identity.keycloakSubject, role)
        val identityId = requireNotNull(identity.id) { "Identity.id must be assigned after save" }
        val now = Instant.now()
        history.save(
            RoleAssignmentHistory(
                id = UUID.randomUUID(),
                identityId = identityId,
                kcSub = identity.keycloakSubject,
                realm = identity.realm,
                role = role,
                action = "grant",
                preset = preset,
                actor = actor,
                cosigner = cosigner,
                breakGlass = breakGlass,
                signature = signature,
                reasonCode = reasonCode,
                endpoint = endpoint,
                targetResource = "identity:$identityId/roles/$role",
                occurredAt = now,
            ),
        )
        publishRoleEvent(identity, role, actor, cosigner, breakGlass, signature, reasonCode, "identity.role.granted.v1", now)
        return listRoles(identityId)
    }

    @Transactional
    fun revoke(
        identity: Identity,
        role: String,
        actor: UUID,
        cosigner: UUID?,
        breakGlass: Boolean,
        signature: String?,
        preset: String?,
        reasonCode: String?,
        endpoint: String,
    ): RoleListResponse {
if (role !in allowedGrantRoles()) throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Role is not grantable by this service")
        val current = keycloak.listRealmRoles(identity.realm, identity.keycloakSubject)
        if (role in current) throw ApiException(HttpStatus.NOT_FOUND, "ROLE_NOT_ASSIGNED", "Role is not assigned")
        keycloak.revokeRealmRole(identity.realm, identity.keycloakSubject, role)
        val identityId = requireNotNull(identity.id) { "Identity.id must be assigned after save" }
        val now = Instant.now()
        history.save(
            RoleAssignmentHistory(
                id = UUID.randomUUID(),
                identityId = identityId,
                kcSub = identity.keycloakSubject,
                realm = identity.realm,
                role = role,
                action = "revoke",
                preset = preset,
                actor = actor,
                cosigner = cosigner,
                breakGlass = breakGlass,
                signature = signature,
                reasonCode = reasonCode,
                endpoint = endpoint,
                targetResource = "identity:$identityId/roles/$role",
                occurredAt = now,
            ),
        )
        publishRoleEvent(identity, role, actor, cosigner, breakGlass, signature, reasonCode, "identity.role.revoked.v1", now)
        return listRoles(identityId)
    }

    private fun publishRoleEvent(
        identity: Identity,
        role: String,
        actor: UUID,
        cosigner: UUID?,
        breakGlass: Boolean,
        signature: String?,
        reasonCode: String?,
        eventName: String,
        occurredAt: Instant,
    ) {
        val identityId = requireNotNull(identity.id) { "Identity.id must be assigned after save" }
        val payload = objectMapper.writeValueAsString(
            mapOf(
                "event_id" to UUID.randomUUID().toString(),
                "event_name" to eventName,
                "aggregate_id" to identityId,
                "occurred_at" to occurredAt.toString(),
                "data" to mapOf(
                    "identity_id" to identityId,
                    "kc_sub" to identity.keycloakSubject,
                    "realm" to identity.realm,
                    "role" to role,
                    "preset" to (if (role in SUPER_ADMIN_PRESET_ROLES) "SUPER_ADMIN" else null),
                    "actor_id" to actor,
                    "cosigner_id" to cosigner,
                    "break_glass" to breakGlass,
                    "signature" to signature,
                    "reason_code" to reasonCode,
                    "correlation_id" to null,
                ),
            ),
        )
        outbox.save(
            OutboxEvent(
                id = UUID.randomUUID(),
                aggregateType = "Identity",
                aggregateId = identityId,
                topic = eventName.substringBeforeLast(".v"),
                eventName = eventName,
                payload = payload,
            ),
        )
    }

    private fun allowedGrantRoles() = setOf(
        "platform.super_admin",
        "platform.admin",
        "platform.support",
        "identity.admin",
        "identity.support",
    )

    private fun presetsMatching(roles: List<String>): List<String> {
        val presets = mutableListOf<String>()
        if (SUPER_ADMIN_PRESET_ROLES.all { it in roles }) presets.add("SUPER_ADMIN")
        return presets
    }

    private fun identity(id: UUID) = identities.findById(id).orElseThrow {
        ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Identity not found")
    }
}

data class RoleListResponse(
    @JsonProperty("identity_id") val identityId: UUID,
    @JsonProperty("kc_sub") val kcSub: String,
    val realm: String,
    val roles: List<String>,
    val presets: List<String>,
    @JsonProperty("evaluated_at") val evaluatedAt: Instant,
)
