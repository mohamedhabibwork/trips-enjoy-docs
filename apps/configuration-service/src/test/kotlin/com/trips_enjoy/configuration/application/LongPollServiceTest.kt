package com.trips_enjoy.configuration.application

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Instant
import java.util.UUID

class LongPollServiceTest {
    private val redis: StringRedisTemplate = org.mockito.kotlin.mock()
    private val mapper = ObjectMapper()
    private val service = LongPollService(redis, mapper, maxWaitSeconds = 1)

    @Test
    fun `await returns empty when no notification arrives within the wait window`() =
        runBlocking {
            val start = System.currentTimeMillis()
            val updates = service.await(UUID.randomUUID(), "pricing.base_fare", null)
            val elapsed = System.currentTimeMillis() - start
            Assertions.assertTrue(updates.isEmpty(), "expected no updates; got $updates")
            // Should have waited ~1s and then returned.
            Assertions.assertTrue(elapsed >= 900, "expected wait ~1s, was ${elapsed}ms")
        }

    @Test
    fun `notifyByDocumentId wakes the awaiting subscriber`() =
        runBlocking {
            val documentId = UUID.randomUUID()
            val started = System.currentTimeMillis()
            // Notify the in-process dispatcher just before the await blocks.
            // Note: due to coroutine scheduling, the safe pattern is to spawn
            // a thread that waits a few ms then notifies.
            val notifier =
                Thread {
                    Thread.sleep(80)
                    service.notifyByDocumentId(
                        documentId,
                        LongPollService.Update(
                            key = "pricing.base_fare",
                            version = 42,
                            value = mapper.createObjectNode().put("amount_minor", 275),
                            changedAt = Instant.now(),
                        ),
                    )
                }
            notifier.start()
            val updates = service.await(documentId, "pricing.base_fare", expectedSinceVersion = null)
            val elapsed = System.currentTimeMillis() - started
            Assertions.assertEquals(1, updates.size)
            Assertions.assertEquals(42L, updates[0].version)
            Assertions.assertTrue(elapsed < 500, "expected fast wake, was ${elapsed}ms")
        }

    @Test
    fun `await drops notifications whose version is not greater than sinceVersion`() =
        runBlocking {
            val documentId = UUID.randomUUID()
            val notifier =
                Thread {
                    Thread.sleep(80)
                    service.notifyByDocumentId(
                        documentId,
                        LongPollService.Update(
                            key = "pricing.base_fare",
                            version = 5,
                            value = mapper.createObjectNode(),
                            changedAt = Instant.now(),
                        ),
                    )
                }
            notifier.start()
            val updates = service.await(documentId, "pricing.base_fare", expectedSinceVersion = 10)
            Assertions.assertTrue(updates.isEmpty(), "expected filter to drop stale update")
        }
}
