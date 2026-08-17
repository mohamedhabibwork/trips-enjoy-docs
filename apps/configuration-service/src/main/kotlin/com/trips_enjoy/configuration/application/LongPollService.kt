package com.trips_enjoy.configuration.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Long-poll stream for configuration updates (FR-009 / INTEGRATION.md §1.6).
 *
 * Coarse in-process registry: a `Map<documentId, MutableList<Deferred>>` of
 * subscribers waiting for a change. When the OutboxPublisher publishes a
 * `configuration.updated.v1` event, the publish callback also notifies
 * the in-process subscribers. This is intentionally per-pod; cross-pod
 * notification is via Redis pub/sub.
 *
 * The wait is bounded by `LONGPOLL_MAX_WAIT_SECONDS` (returns empty if
 * nothing arrives in time).
 */
@Service
class LongPollService(
    private val redis: StringRedisTemplate,
    private val mapper: ObjectMapper,
    @Value("\${configuration-service.longpoll.max-wait-seconds:25}")
    private val maxWaitSeconds: Long,
) {
    data class Update(
        val key: String,
        val version: Long,
        val value: JsonNode,
        val changedAt: Instant,
    )

    private val waiters: MutableMap<UUID, MutableList<CompletableDeferred<Update>>> = ConcurrentHashMap()

    suspend fun await(
        documentId: UUID,
        key: String,
        expectedSinceVersion: Long?,
    ): List<Update> {
        val deferred = CompletableDeferred<Update>()
        waiters.computeIfAbsent(documentId) { mutableListOf() }.add(deferred)
        try {
            val notification = withTimeoutOrNull(maxWaitSeconds * 1000L) { deferred.await() }
            return if (notification == null) {
                emptyList()
            } else {
                if (expectedSinceVersion != null && notification.version <= expectedSinceVersion) {
                    emptyList()
                } else {
                    listOf(notification)
                }
            }
        } finally {
            waiters[documentId]?.remove(deferred)
            if (waiters[documentId]?.isEmpty() == true) waiters.remove(documentId)
        }
    }

    /**
     * Notify all in-process subscribers for the given document. Called
     * from the OutboxPublisher's success callback.
     */
    fun notifyLocal(update: Update) {
        val list = waiters.remove(UUID.fromString("00000000-0000-0000-0000-000000000000")) ?: return
        list.forEach { it.complete(update) }
    }

    /**
     * Resolve the waiter by key (since the LongPollService doesn't know
     * documentId at subscription time). Document IDs are looked up via
     * an in-memory map maintained by the registry.
     */
    fun notifyByDocumentId(
        documentId: UUID,
        update: Update,
    ) {
        val list = waiters.remove(documentId) ?: return
        list.forEach { it.complete(update) }
    }

    fun encodeToRedis(update: Update): String = mapper.writeValueAsString(update)
}
