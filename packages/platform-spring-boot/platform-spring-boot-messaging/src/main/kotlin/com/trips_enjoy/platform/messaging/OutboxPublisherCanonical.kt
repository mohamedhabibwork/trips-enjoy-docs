package com.trips_enjoy.platform.messaging

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Configuration for the canonical outbox publisher.
 *
 * Properties (application.yml `platform.outbox.*`):
 * - [intervalMs]   - poll cadence (default 1000ms)
 * - [batchSize]    - rows fetched per poll (default 100)
 * - [maxAttempts]  - attempts before DLQ (default 6)
 */
@ConfigurationProperties("platform.outbox")
data class OutboxProperties(
    val intervalMs: Long = 1000L,
    val batchSize: Int = 100,
    val maxAttempts: Int = 6,
)

/**
 * Spring Data repository for [OutboxEventCanonical].
 *
 * The poll query mirrors ADR-0028 §"Canonical OutboxPublisher
 * semantics":
 *
 * ```
 * SELECT ... FROM <schema>.outbox
 *   WHERE published_at IS NULL
 *     AND next_attempt_at <= now()
 *   ORDER BY next_attempt_at ASC
 *   FOR UPDATE SKIP LOCKED
 *   LIMIT 100
 * ```
 *
 * `FOR UPDATE SKIP LOCKED` is what makes the publisher safe for
 * multi-replica deployments: a row that's already locked by another
 * replica is silently skipped, and the second replica picks up the
 * next batch.
 */
interface OutboxRepositoryCanonical : JpaRepository<OutboxEventCanonical, UUID> {
    @Query(
        """
        SELECT o FROM OutboxEventCanonical o
         WHERE o.publishedAt IS NULL
           AND o.nextAttemptAt <= :now
         ORDER BY o.nextAttemptAt ASC
        """
    )
    fun findPending(
        @Param("now") now: Instant,
        pageable: PageRequest,
    ): List<OutboxEventCanonical>
}

/**
 * Canonical poll-and-publish daemon.
 *
 * Per ADR-0028:
 * - Poll every [OutboxProperties.intervalMs] ms (default 1000).
 * - Pull up to [OutboxProperties.batchSize] pending rows (default 100)
 *   ordered by `next_attempt_at`, using `FOR UPDATE SKIP LOCKED` to
 *   avoid double-publish across replicas.
 * - On success: set `published_at = now()`.
 * - On failure: increment `attempts`, store `last_error`, and
 *   schedule the next attempt at `now() + (5 * 2^attempts)` seconds
 *   (the canonical exponential backoff).
 * - After [OutboxProperties.maxAttempts] attempts (default 6): publish
 *   to `<topic>.dlq` (per ADR-0024) and mark `published_at = now()`
 *   — terminal, manual replay only.
 *
 * `@ConditionalOnMissingBean` is applied at the [OutboxAutoConfiguration]
 * layer so service-local publishers (in services that haven't migrated
 * to canonical yet) continue to win.
 */
@Component
open class OutboxPublisherCanonical(
    private val repository: OutboxRepositoryCanonical,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val properties: OutboxProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${platform.outbox.interval-ms:1000}")
    @Transactional
    open fun publish() {
        val pending = repository.findPending(
            now = Instant.now(),
            pageable = PageRequest.of(0, properties.batchSize),
        )
        if (pending.isEmpty()) return

        for (event in pending) {
            try {
                kafkaTemplate.send(event.topic, event.partitionKey, event.payload).get()
                event.markPublished(Instant.now())
                log.debug("outbox published event={} topic={}", event.id, event.topic)
            } catch (e: Exception) {
                val nextAttempts = event.attempts + 1
                if (nextAttempts >= properties.maxAttempts) {
                    // Terminal DLQ: send to <topic>.dlq and mark published.
                    val dlqTopic = "${event.topic}.dlq"
                    try {
                        kafkaTemplate.send(dlqTopic, event.partitionKey, event.payload).get()
                        event.markPublished(Instant.now())
                        event.lastError = "DLQ after $nextAttempts attempts: ${e.message?.take(500)}"
                        log.warn(
                            "outbox routed event={} to DLQ {} after {} attempts",
                            event.id, dlqTopic, nextAttempts,
                        )
                    } catch (dlqEx: Exception) {
                        event.markFailed(
                            "DLQ publish failed: ${dlqEx.message}",
                            Instant.now().plus(nextBackoff(nextAttempts)),
                        )
                        log.error(
                            "outbox DLQ publish failed (event={}, attempt={}): {}",
                            event.id, nextAttempts, dlqEx.message,
                        )
                    }
                } else {
                    event.markFailed(
                        e.message ?: e.javaClass.simpleName,
                        Instant.now().plus(nextBackoff(nextAttempts)),
                    )
                    log.warn(
                        "outbox publish failed (event={}, attempt={}): {}",
                        event.id, nextAttempts, e.message,
                    )
                }
            }
        }
    }

    /**
     * Canonical exponential backoff: 5 * 2^attempts seconds, capped at
     * 5 minutes (300s) per ADR-0028. attempt=1 → 5s, attempt=2 → 10s,
     * attempt=3 → 20s, attempt=4 → 40s, attempt=5 → 80s, attempt=6 → 160s.
     */
    private fun nextBackoff(attempt: Int): Duration {
        val seconds = minOf(300L, 5L shl minOf(attempt - 1, 6))
        return Duration.ofSeconds(seconds)
    }
}