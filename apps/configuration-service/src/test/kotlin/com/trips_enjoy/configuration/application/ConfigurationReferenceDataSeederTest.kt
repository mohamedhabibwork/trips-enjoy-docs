package com.trips_enjoy.configuration.application

import com.trips_enjoy.configuration.domain.OutboxEvent
import com.trips_enjoy.configuration.domain.OutboxRepository
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.boot.ApplicationArguments
import org.springframework.data.domain.PageRequest
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture

class ConfigurationReferenceDataSeederTest {
    private val outboxRepository: OutboxRepository = mock()
    private val kafka: KafkaTemplate<String, String> = mock()
    private val seeder =
        ConfigurationReferenceDataSeeder(
            outboxRepository = outboxRepository,
            kafka = kafka,
            activeProfile = "dev",
            profileAllowlist = listOf("dev", "local", "test"),
        )

    @Test
    fun `publishes every unpublished outbox row to Kafka and marks published_at`() {
        val e1 =
            OutboxEvent(
                id = UUID.randomUUID(),
                topic = "configuration.updated",
                eventId = UUID.randomUUID(),
                payload = """{"event_name":"configuration.updated.v1","data":{"key":"a"}}""",
            )
        val e2 =
            OutboxEvent(
                id = UUID.randomUUID(),
                topic = "configuration.updated",
                eventId = UUID.randomUUID(),
                payload = """{"event_name":"configuration.updated.v1","data":{"key":"b"}}""",
            )
        whenever(outboxRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc(PageRequest.of(0, 100)))
            .thenReturn(listOf(e1, e2))
        whenever(kafka.send(any<String>(), any<String>(), any<String>()))
            .thenReturn(CompletableFuture.completedFuture(mock<SendResult<String, String>>()))

        seeder.run(mock<ApplicationArguments>())

        verify(kafka).send("configuration.updated", e1.id.toString(), e1.payload)
        verify(kafka).send("configuration.updated", e2.id.toString(), e2.payload)
        Assertions.assertNotNull(e1.publishedAt)
        Assertions.assertNotNull(e2.publishedAt)
    }

    @Test
    fun `is a no-op when there are no unpublished outbox rows`() {
        whenever(outboxRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc(PageRequest.of(0, 100)))
            .thenReturn(emptyList())

        seeder.run(mock<ApplicationArguments>())

        verify(kafka, never()).send(any<String>(), any<String>(), any<String>())
    }

    @Test
    fun `refuses to publish when active profile is not in allowlist`() {
        val denySeeder =
            ConfigurationReferenceDataSeeder(
                outboxRepository = outboxRepository,
                kafka = kafka,
                activeProfile = "prod",
                profileAllowlist = listOf("dev", "local", "test"),
            )

        denySeeder.run(mock<ApplicationArguments>())

        verify(outboxRepository, never()).findTop100ByPublishedAtIsNullOrderByCreatedAtAsc(any())
        verify(kafka, never()).send(any<String>(), any<String>(), any<String>())
    }

    @Test
    fun `allows a production-like profile when explicitly opted in via profile-allowlist`() {
        val optedInSeeder =
            ConfigurationReferenceDataSeeder(
                outboxRepository = outboxRepository,
                kafka = kafka,
                activeProfile = "stg",
                profileAllowlist = listOf("dev", "stg", "prod"), // operator opted-in
            )
        whenever(outboxRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc(PageRequest.of(0, 100)))
            .thenReturn(emptyList())

        optedInSeeder.run(mock<ApplicationArguments>())

        // Allowed by the allowlist → empty list, so no publish — but the
        // seeder does look up the outbox (proving it didn't refuse).
        verify(outboxRepository).findTop100ByPublishedAtIsNullOrderByCreatedAtAsc(any())
        verify(kafka, never()).send(any<String>(), any<String>(), any<String>())
    }

    @Test
    fun `continues processing after a publish failure`() {
        val e1 =
            OutboxEvent(
                id = UUID.randomUUID(),
                topic = "configuration.updated",
                eventId = UUID.randomUUID(),
                payload = """{"event_name":"configuration.updated.v1","data":{"key":"a"}}""",
            )
        val e2 =
            OutboxEvent(
                id = UUID.randomUUID(),
                topic = "configuration.updated",
                eventId = UUID.randomUUID(),
                payload = """{"event_name":"configuration.updated.v1","data":{"key":"b"}}""",
            )
        whenever(outboxRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc(PageRequest.of(0, 100)))
            .thenReturn(listOf(e1, e2))
        // First send fails, second succeeds.
        val failedFuture = CompletableFuture<SendResult<String, String>>()
        failedFuture.completeExceptionally(RuntimeException("Kafka unreachable"))
        whenever(kafka.send(any<String>(), any<String>(), any<String>()))
            .thenReturn(failedFuture)
            .thenReturn(CompletableFuture.completedFuture(mock<SendResult<String, String>>()))

        // Should not throw even though e1 fails.
        seeder.run(mock<ApplicationArguments>())

        // e2 was successfully published and marked.
        Assertions.assertNotNull(e2.publishedAt)
        Assertions.assertNull(e1.publishedAt)
    }

    @Test
    fun `handles Instant now resolution correctly for published_at`() {
        val e1 =
            OutboxEvent(
                id = UUID.randomUUID(),
                topic = "configuration.updated",
                eventId = UUID.randomUUID(),
                payload = """{"event_name":"configuration.updated.v1"}""",
            )
        whenever(outboxRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc(PageRequest.of(0, 100)))
            .thenReturn(listOf(e1))
        whenever(kafka.send(any<String>(), any<String>(), any<String>()))
            .thenReturn(CompletableFuture.completedFuture(mock<SendResult<String, String>>()))

        val before = Instant.now()
        seeder.run(mock<ApplicationArguments>())
        val after = Instant.now()

        Assertions.assertNotNull(e1.publishedAt)
        Assertions.assertTrue(
            e1.publishedAt!! >= before && e1.publishedAt!! <= after,
            "expected publishedAt between $before and $after, was ${e1.publishedAt}",
        )
    }
}
