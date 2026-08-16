package com.trips_enjoy.identity.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface IdentityRepository : JpaRepository<Identity, UUID> {
    fun findByKeycloakSubjectAndRealmAndDeletedAtIsNull(keycloakSubject: String, realm: String): Identity?

    /** Returns a row for `(kc_sub, realm)` regardless of soft-delete status. */
    fun findByKeycloakSubjectAndRealm(keycloakSubject: String, realm: String): Identity?

    fun findByIdAndDeletedAtIsNull(id: UUID): Identity?
}
