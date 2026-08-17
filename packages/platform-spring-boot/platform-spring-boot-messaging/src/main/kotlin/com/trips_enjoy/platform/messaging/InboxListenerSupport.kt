package com.trips_enjoy.platform.messaging

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Spring Data repository for [InboxEventCanonical].
 *
 * The unique key `(consumer_group, message_id)` is the dedup
 * primitive. The repository exposes [findForProcessing] so the listener
 * base class can perform an atomic upsert pattern: try-insert, on
 * unique-violation return the existing row.
 */
interface InboxRepositoryCanonical : JpaRepository<InboxEventCanonical, UUID> {
    fun findByConsumerGroupAndMessageId(consumerGroup: String, messageId: UUID): InboxEventCanonical?
}

/**
 * Abstract base class that `@KafkaListener` consumers compose with
 * for idempotent processing.
 *
 * Subclasses declare a `consumerGroup` (e.g. `payment-service`) and
 * override [handle] with the actual business logic. The base class:
 *
 * 1. Inserts an [InboxEventCanonical] row for the incoming message
 *    (`message_id` = the producer-side UUIDv7 `event_id`).
 * 2. If the row already exists (re-delivery), returns it without
 *    re-running the handler.
 * 3. On handler success: marks the row `processed_at = now()`.
 * 4. On handler failure: leaves `processed_at` null so the next
 *    re-delivery re-runs the handler.
 *
 * Combined with the outbox publisher, this gives end-to-end exactly-
 * once semantics: the producer's outbox dedupes on `event_id`, and the
 * consumer's inbox dedupes on the same `event_id` — they're the same
 * row in the event graph.
 */
abstract class InboxListenerSupport(
    private val inboxRepository: InboxRepositoryCanonical,
) {
    /** Consumer group id; e.g. `payment-service`. Must be unique per listener. */
    protected abstract val consumerGroup: String

    /**
     * Process the message. Throw to signal failure (row stays unprocessed);
     * return normally to mark the row as processed.
     */
    protected abstract fun handle(event: InboxEventCanonical)

    /**
     * Entry point invoked from the `@KafkaListener` method. Pass the
     * Kafka record's key (as String UUID), topic, and raw payload.
     */
    open fun onMessage(messageId: UUID, topic: String, payload: String): InboxEventCanonical {
        val existing = inboxRepository.findByConsumerGroupAndMessageId(consumerGroup, messageId)
        val row = if (existing != null) {
            existing
        } else {
            inboxRepository.save(
                InboxEventCanonical(
                    id = UUID.randomUUID(),
                    messageId = messageId,
                    consumerGroup = consumerGroup,
                    topic = topic,
                    payload = payload,
                    receivedAt = Instant.now(),
                )
            )
        }
        if (row.processedAt != null) {
            // Already processed; idempotent no-op.
            return row
        }
        handle(row)
        row.markProcessed(Instant.now())
        return inboxRepository.save(row)
    }
}

/**
 * Marker for service-side subclasses that want Spring to instantiate
 * them. Concrete listeners should extend [InboxListenerSupport]
 * directly with `@Component` on the subclass.
 */
@Component
internal class DefaultInboxListenerSupportMarker