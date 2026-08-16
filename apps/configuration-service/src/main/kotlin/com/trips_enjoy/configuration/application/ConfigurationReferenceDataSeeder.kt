package com.trips_enjoy.configuration.application

import com.trips_enjoy.configuration.domain.OutboxRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/**
 * Production reference-data seeder for configuration-service.
 *
 * Companion to V8__configuration_seed_reference_data.sql. The migration
 * inserts the canonical default rows (locked commission keys + retention /
 * session / retry / per-city defaults) and the matching
 * `configuration.updated.v1` outbox events. The seeder's job is to:
 *
 *  1. Run AFTER Flyway so the outbox rows exist.
 *  2. Publish each unpublished outbox row to Kafka so downstream services
 *     get a `configuration.updated.v1` for every seeded key and start with
 *     a warm cache (FR-015 / INTEGRATION.md §3.1).
 *  3. Mark each published row's `published_at` so a re-run is idempotent.
 *
 * Activated only when:
 *   - `configuration-service.seed.enabled=true` (default false).
 *   - The active Spring profile is NOT in the deny-list `prod`, `stg`,
 *     `live`, OR the operator sets `configuration-service.seed.profile-allowlist`
 *     explicitly to opt a production-like profile in.
 *
 * This mirrors the [AuditDevDataSeeder] pattern but the configuration
 * version is simpler: the migration already writes valid payloads, so the
 * seeder only needs to forward them to Kafka.
 */
@Component
@ConditionalOnProperty(name = ["configuration-service.seed.enabled"], havingValue = "true")
@Order(Ordered.LOWEST_PRECEDENCE)
class ConfigurationReferenceDataSeeder(
    private val outboxRepository: OutboxRepository,
    private val kafka: KafkaTemplate<String, String>,
    @Value("\${spring.profiles.active:dev}") private val activeProfile: String,
    @Value("\${configuration-service.seed.profile-allowlist:dev,local,test,ci}")
    private val profileAllowlist: List<String>,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        if (activeProfile !in profileAllowlist) {
            log.warn(
                "ConfigurationReferenceDataSeeder is enabled but profile '{}' is not in allowlist {}; " +
                    "refusing to publish seed events. Set configuration-service.seed.profile-allowlist " +
                    "to opt-in for this profile.",
                activeProfile,
                profileAllowlist,
            )
            return
        }
        val pending =
            outboxRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc(
                org.springframework.data.domain.PageRequest
                    .of(0, 100),
            )
        if (pending.isEmpty()) {
            log.info("ConfigurationReferenceDataSeeder: no unpublished outbox rows; nothing to publish.")
            return
        }
        var published = 0
        var failed = 0
        for (event in pending) {
            try {
                // The migration wrote the seed payload in production-ready form
                // (event_id, envelope, aggregate_id). Forward verbatim.
                kafka.send(event.topic, event.id.toString(), event.payload).get()
                event.publishedAt = java.time.Instant.now()
                published++
            } catch (exception: Exception) {
                failed++
                log.warn(
                    "Failed to publish seed event {} to topic {}: {}",
                    event.id,
                    event.topic,
                    exception.message,
                )
            }
        }
        log.info(
            "ConfigurationReferenceDataSeeder: published {} seed events ({} failed) for profile '{}'",
            published,
            failed,
            activeProfile,
        )
    }
}
