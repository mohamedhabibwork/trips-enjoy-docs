package com.trips_enjoy.identity.integration

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import com.trips_enjoy.platform.test.BaseIntegrationTest
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Validates the immutability triggers added in V4:
 *   - identity.identity_audit_log: append-only (UPDATE/DELETE rejected)
 *   - identity.role_assignment_history: append-only (UPDATE/DELETE rejected)
 *   - identity.identity_claim_history: append-only (DELETE rejected)
 *
 * Per SRS §21 ("identity_audit_log is immutable; no UPDATE / DELETE permitted")
 * and ERD §3 ("REVOKE UPDATE, DELETE").
 */
@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
class IdentitySchemaImmutabilityIT : BaseIntegrationTest() {

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private fun seedIdentity(id: UUID, now: Instant) {
        jdbc.update(
            "INSERT INTO identity.identities (id, kc_sub, realm, user_type, status, email_verified, phone_verified, mfa_enabled, created_by, updated_by, created_at, updated_at) VALUES (?, ?, 'platform-internal', 'admin', 'ACTIVE', false, false, false, ?, ?, ?, ?)",
            id,
            "kc-sub-immut-${id}",
            UUID(0, 0),
            UUID(0, 0),
            Timestamp.from(now),
            Timestamp.from(now),
        )
    }

    private fun insertAuditLog(id: UUID, identityId: UUID, now: Instant) {
        jdbc.update(
            "INSERT INTO identity.identity_audit_log (id, identity_id, action, actor, actor_type, occurred_at) VALUES (?, ?, 'create', ?, 'service', ?)",
            id,
            identityId,
            UUID(0, 0),
            Timestamp.from(now),
        )
    }

    private fun insertClaimHistory(id: UUID, identityId: UUID, now: Instant) {
        jdbc.update(
            "INSERT INTO identity.identity_claim_history (id, identity_id, field, source, changed_by, changed_at) VALUES (?, ?, 'name', 'service', ?, ?)",
            id,
            identityId,
            UUID(0, 0),
            Timestamp.from(now),
        )
    }

    private fun insertRoleAssignmentHistory(id: UUID, identityId: UUID, now: Instant) {
        jdbc.update(
            "INSERT INTO identity.role_assignment_history (id, identity_id, kc_sub, realm, role, action, actor, endpoint, target_resource, occurred_at) VALUES (?, ?, 'sub', 'platform-internal', 'platform.admin', 'grant', ?, '/admin/v1/identities/{id}/roles/{role}', 'identity:abc/roles/platform.admin', ?)",
            id,
            identityId,
            UUID(0, 0),
            Timestamp.from(now),
        )
    }

    @Test
    fun `identity_audit_log rejects UPDATE`() {
        val identityId = UUID.randomUUID()
        val id = UUID.randomUUID()
        val now = Instant.now()
        seedIdentity(identityId, now)
        insertAuditLog(id, identityId, now)
        assertThrows(Exception::class.java) {
            jdbc.update("UPDATE identity.identity_audit_log SET action = 'tamper' WHERE id = ?", id)
        }
    }

    @Test
    fun `identity_audit_log rejects DELETE`() {
        val identityId = UUID.randomUUID()
        val id = UUID.randomUUID()
        val now = Instant.now()
        seedIdentity(identityId, now)
        insertAuditLog(id, identityId, now)
        assertThrows(Exception::class.java) {
            jdbc.update("DELETE FROM identity.identity_audit_log WHERE id = ?", id)
        }
    }

    @Test
    fun `role_assignment_history rejects UPDATE`() {
        val identityId = UUID.randomUUID()
        val id = UUID.randomUUID()
        val now = Instant.now()
        seedIdentity(identityId, now)
        insertRoleAssignmentHistory(id, identityId, now)
        assertThrows(Exception::class.java) {
            jdbc.update("UPDATE identity.role_assignment_history SET action = 'tamper' WHERE id = ?", id)
        }
    }

    @Test
    fun `identity_claim_history rejects DELETE`() {
        val identityId = UUID.randomUUID()
        val id = UUID.randomUUID()
        val now = Instant.now()
        seedIdentity(identityId, now)
        insertClaimHistory(id, identityId, now)
        assertThrows(Exception::class.java) {
            jdbc.update("DELETE FROM identity.identity_claim_history WHERE id = ?", id)
        }
    }
}
