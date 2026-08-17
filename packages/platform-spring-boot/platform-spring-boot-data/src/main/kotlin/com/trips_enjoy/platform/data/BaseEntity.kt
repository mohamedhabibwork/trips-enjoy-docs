package com.trips_enjoy.platform.data

import jakarta.persistence.Column
import jakarta.persistence.EntityListeners
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.Version
import org.hibernate.annotations.UuidGenerator
import org.springframework.data.annotation.CreatedBy
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedBy
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import java.time.Instant
import java.util.UUID

/**
 * Base entity for all JPA-mapped domain objects. Provides:
 *   - UUIDv7 `id` (UUIDv7-stdlib is added in each consuming service)
 *   - `createdAt` / `updatedAt` (UTC timestamptz, populated by AuditingEntityListener)
 *   - `createdBy` / `updatedBy` (JWT `sub` from AuditorAware)
 *   - `version` (optimistic-lock counter)
 *   - `deletedAt` (soft-delete column; null = active)
 *
 * The first consumer to migrate is `ledger-service` (see `data-svc` migration plan).
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener::class)
abstract class BaseEntity {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    var id: UUID? = null

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null

    @LastModifiedDate
    @Column(name = "updated_at")
    var updatedAt: Instant? = null

    @CreatedBy
    @Column(name = "created_by", updatable = false, length = 255)
    var createdBy: String? = null

    @LastModifiedBy
    @Column(name = "updated_by", length = 255)
    var updatedBy: String? = null

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null
}

/**
 * Marker import to enable JPA auditing in the consuming service. Add
 * `@EnableJpaAuditing` to a `@Configuration` class and pair with a
 * `PlatformAuditorAware<String>` bean (see [AuditorAware]).
 */
@EnableJpaAuditing
internal class JpaAuditingConfiguration
