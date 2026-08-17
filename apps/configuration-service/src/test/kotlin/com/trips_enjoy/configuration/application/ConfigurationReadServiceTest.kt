package com.trips_enjoy.configuration.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.configuration.api.ApiException
import com.trips_enjoy.configuration.domain.ConfigurationVersion
import com.trips_enjoy.configuration.domain.ConfigurationVersionPk
import com.trips_enjoy.configuration.domain.ConfigurationVersionRepository
import com.trips_enjoy.configuration.domain.Document
import com.trips_enjoy.configuration.domain.DocumentRepository
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.data.domain.PageRequest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Instant
import java.util.Optional
import java.util.UUID

class ConfigurationReadServiceTest {
    private val documentRepository: DocumentRepository = mock()
    private val versionRepository: ConfigurationVersionRepository = mock()
    private val redis: StringRedisTemplate = mock()
    private val redisValueOps: ValueOperations<String, String> = mock()
    private val mapper = ObjectMapper()
    private val service = ConfigurationReadService(documentRepository, versionRepository, redis, mapper, 300)

    @Test
    fun `resolve throws CONFIG_KEY_NOT_FOUND when key is missing`() {
        whenever(documentRepository.findByKey("missing.key")).thenReturn(Optional.empty())
        val ex =
            Assertions.assertThrows(ApiException::class.java) {
                service.resolve("missing.key", emptyMap(), UUID.randomUUID())
            }
        Assertions.assertEquals("CONFIG_KEY_NOT_FOUND", ex.code)
    }

    @Test
    fun `resolve throws CONFIG_KEY_NOT_FOUND when key is soft-deleted`() {
        val doc = newDocument(deactivatedAt = Instant.now())
        whenever(documentRepository.findByKey("deleted.key")).thenReturn(Optional.of(doc))
        val ex =
            Assertions.assertThrows(ApiException::class.java) {
                service.resolve("deleted.key", emptyMap(), UUID.randomUUID())
            }
        Assertions.assertEquals("CONFIG_KEY_NOT_FOUND", ex.code)
    }

    @Test
    fun `resolve prefers the most-specific scope over global`() {
        val doc = newDocument()
        whenever(documentRepository.findByKey("pricing.base_fare")).thenReturn(Optional.of(doc))
        whenever(redis.opsForValue()).thenReturn(redisValueOps)
        whenever(redisValueOps.get(any())).thenReturn(null)
        whenever(
            versionRepository.findAllByDocument(doc.id, PageRequest.of(0, 1)),
        ).thenReturn(
            listOf(
                newVersion(doc.id, version = 1, scopeType = "global", scopeId = null),
                newVersion(doc.id, version = 2, scopeType = "city", scopeId = "amsterdam"),
            ),
        )
        val resolved =
            service.resolve(
                "pricing.base_fare",
                mapOf("city" to "amsterdam"),
                UUID.randomUUID(),
            )
        Assertions.assertEquals("city", resolved.matchedScopeType)
        Assertions.assertEquals("amsterdam", resolved.matchedScopeId)
        Assertions.assertEquals(2L, resolved.version)
    }

    @Test
    fun `resolve returns global when no scope in context matches`() {
        val doc = newDocument()
        whenever(documentRepository.findByKey("pricing.base_fare")).thenReturn(Optional.of(doc))
        whenever(redis.opsForValue()).thenReturn(redisValueOps)
        whenever(redisValueOps.get(any())).thenReturn(null)
        whenever(versionRepository.findAllByDocument(doc.id, PageRequest.of(0, 1))).thenReturn(
            listOf(newVersion(doc.id, version = 1, scopeType = "global", scopeId = null)),
        )
        val resolved =
            service.resolve(
                "pricing.base_fare",
                mapOf("city" to "amsterdam"),
                UUID.randomUUID(),
            )
        Assertions.assertEquals("global", resolved.matchedScopeType)
        Assertions.assertEquals(1L, resolved.version)
    }

    private fun newDocument(deactivatedAt: Instant? = null): Document {
        val now = Instant.now()
        return Document(
            id = UUID.randomUUID(),
            key = "pricing.base_fare",
            tenantId = "global",
            currentVersion = 1,
            schemaId = UUID.randomUUID(),
            value = """{"amount_minor":250,"currency":"EUR"}""",
            valueType = "object",
            deactivatedAt = deactivatedAt,
            createdAt = now,
            updatedAt = now,
            createdBy = UUID.randomUUID(),
            updatedBy = UUID.randomUUID(),
        )
    }

    private fun newVersion(
        documentId: UUID,
        version: Long,
        scopeType: String,
        scopeId: String?,
    ): ConfigurationVersion =
        ConfigurationVersion(
            pk = ConfigurationVersionPk(id = UUID.randomUUID(), createdAt = Instant.now()),
            documentId = documentId,
            version = version,
            value = """{"amount_minor":250,"currency":"EUR"}""",
            scopeType = scopeType,
            scopeId = scopeId,
            reason = "test",
            correlationId = UUID.randomUUID(),
            actorId = UUID.randomUUID(),
        )
}
