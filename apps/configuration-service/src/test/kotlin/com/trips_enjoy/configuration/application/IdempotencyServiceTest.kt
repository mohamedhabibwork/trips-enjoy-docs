package com.trips_enjoy.configuration.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.configuration.domain.Idempotency
import com.trips_enjoy.configuration.domain.IdempotencyRepository
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.Optional
import java.util.UUID

class IdempotencyServiceTest {
    private val repository: IdempotencyRepository = mock()
    private val mapper = ObjectMapper()
    private val service = IdempotencyService(repository, mapper, ttlSeconds = 86400)

    @Test
    fun `find returns the cached row when the key exists`() {
        val key = UUID.randomUUID()
        val stored =
            Idempotency(
                idempotencyKey = key,
                requestHash = "hash",
                responseStatus = 201,
                responseBody = """{"key":"pricing.base_fare"}""",
                actorId = UUID.randomUUID(),
                createdAt = Instant.now(),
                expiresAt = Instant.now().plusSeconds(86400),
            )
        whenever(repository.findById(key)).thenReturn(Optional.of(stored))
        val result = service.find(key)
        Assertions.assertTrue(result.isPresent)
        Assertions.assertEquals(201, result.get().responseStatus)
    }

    @Test
    fun `find returns empty when the key is unknown`() {
        val key = UUID.randomUUID()
        whenever(repository.findById(key)).thenReturn(Optional.empty())
        val result = service.find(key)
        Assertions.assertTrue(result.isEmpty())
    }

    @Test
    fun `record writes a row with ttl-seconds-ahead expires_at`() {
        val key = UUID.randomUUID()
        val actor = UUID.randomUUID()
        val before = Instant.now()
        service.record(
            key = key,
            requestHash = "hash",
            actorId = actor,
            responseStatus = 201,
            responseBody = mapOf("ok" to true),
        )
        // Mockito captures the argument; verify the expires_at is roughly
        // ttl-seconds ahead of the call time.
        val captor = org.mockito.kotlin.argumentCaptor<Idempotency>()
        org.mockito.kotlin
            .verify(repository)
            .save(captor.capture())
        val saved = captor.firstValue
        val after = Instant.now()
        Assertions.assertEquals(key, saved.idempotencyKey)
        Assertions.assertEquals(actor, saved.actorId)
        Assertions.assertEquals(201, saved.responseStatus)
        Assertions.assertTrue(
            saved.expiresAt >= before.plusSeconds(86_399) && saved.expiresAt <= after.plusSeconds(86_401),
            "expected expiresAt ~24h ahead, was ${saved.expiresAt}",
        )
    }

    @Test
    fun `purgeExpired delegates to the repository with current time`() {
        whenever(repository.deleteAllByExpiresAtBefore(any())).thenReturn(7L)
        val count = service.purgeExpired()
        Assertions.assertEquals(7L, count)
    }
}
