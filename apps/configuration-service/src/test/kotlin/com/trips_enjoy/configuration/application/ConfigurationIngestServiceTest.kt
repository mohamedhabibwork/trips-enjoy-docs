package com.trips_enjoy.configuration.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.configuration.api.ApiException
import com.trips_enjoy.configuration.domain.ConfigurationAuditLogRepository
import com.trips_enjoy.configuration.domain.ConfigurationSchemaRepository
import com.trips_enjoy.configuration.domain.ConfigurationVersionRepository
import com.trips_enjoy.configuration.domain.Document
import com.trips_enjoy.configuration.domain.DocumentRepository
import com.trips_enjoy.configuration.domain.OutboxRepository
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.Optional
import java.util.UUID

class ConfigurationIngestServiceTest {
    private val documentRepository: DocumentRepository = mock()
    private val versionRepository: ConfigurationVersionRepository = mock()
    private val schemaRepository: ConfigurationSchemaRepository = mock()
    private val auditLogRepository: ConfigurationAuditLogRepository = mock()
    private val outboxRepository: OutboxRepository = mock()
    private val mapper = ObjectMapper()
    private val schemaValidation = SchemaValidationService(mapper)
    private val service =
        ConfigurationIngestService(
            documentRepository = documentRepository,
            versionRepository = versionRepository,
            schemaRepository = schemaRepository,
            auditLogRepository = auditLogRepository,
            outboxRepository = outboxRepository,
            schemaValidationService = schemaValidation,
            mapper = mapper,
        )

    @BeforeEach
    fun resetMocks() {
        // Mockito inline mocks reset automatically between tests.
    }

    @Test
    fun `createKey throws CONFIG_KEY_EXISTS when key is already present`() {
        whenever(documentRepository.findByKey("pricing.base_fare")).thenReturn(Optional.of(newDocument()))
        val ex =
            Assertions.assertThrows(ApiException::class.java) {
                service.createKey(
                    key = "pricing.base_fare",
                    schema = mapper.readTree("""{"type":"object"}"""),
                    value = mapper.readTree("""{}"""),
                    scopeType = "global",
                    scopeId = null,
                    reason = "Initial test creation",
                    actorId = UUID.randomUUID(),
                    actorIp = null,
                    correlationId = UUID.randomUUID(),
                )
            }
        Assertions.assertEquals("CONFIG_KEY_EXISTS", ex.code)
    }

    @Test
    fun `putVersion throws CONFIG_KEY_NOT_FOUND when key is missing`() {
        whenever(documentRepository.lockByKey("missing.key")).thenReturn(Optional.empty())
        val ex =
            Assertions.assertThrows(ApiException::class.java) {
                service.putVersion(
                    key = "missing.key",
                    value = mapper.readTree("""{}"""),
                    scopeType = "global",
                    scopeId = null,
                    cohort = null,
                    effectiveFrom = null,
                    effectiveTo = null,
                    expectedCurrentVersion = 0,
                    reason = "Attempted update on missing key",
                    actorId = UUID.randomUUID(),
                    actorIp = null,
                    correlationId = UUID.randomUUID(),
                )
            }
        Assertions.assertEquals("CONFIG_KEY_NOT_FOUND", ex.code)
    }

    @Test
    fun `putVersion throws VERSION_CONFLICT on stale expected version`() {
        val doc = newDocument(version = 7)
        whenever(documentRepository.lockByKey("pricing.base_fare")).thenReturn(Optional.of(doc))
        val ex =
            Assertions.assertThrows(ApiException::class.java) {
                service.putVersion(
                    key = "pricing.base_fare",
                    value = mapper.readTree("""{}"""),
                    scopeType = "global",
                    scopeId = null,
                    cohort = null,
                    effectiveFrom = null,
                    effectiveTo = null,
                    expectedCurrentVersion = 5,
                    reason = "Should fail on version mismatch",
                    actorId = UUID.randomUUID(),
                    actorIp = null,
                    correlationId = UUID.randomUUID(),
                )
            }
        Assertions.assertEquals("VERSION_CONFLICT", ex.code)
    }

    @Test
    fun `putVersion throws VALIDATION_FAILED when schema rejects the value`() {
        val doc = newDocument(version = 1)
        whenever(documentRepository.lockByKey("pricing.base_fare")).thenReturn(Optional.of(doc))
        whenever(schemaRepository.maxVersionForKey(any())).thenReturn(1)
        whenever(schemaRepository.findByKeyAndVersion(any(), any())).thenReturn(
            Optional.of(
                com.trips_enjoy.configuration.domain.ConfigurationSchema(
                    id = UUID.randomUUID(),
                    key = "pricing.base_fare",
                    version = 1,
                    jsonSchema = """{"type":"object","required":["amount_minor"],"properties":{"amount_minor":{"type":"integer"}}}""",
                    createdBy = UUID.randomUUID(),
                ),
            ),
        )
        val ex =
            Assertions.assertThrows(ApiException::class.java) {
                service.putVersion(
                    key = "pricing.base_fare",
                    value = mapper.readTree("""{}"""),
                    scopeType = "global",
                    scopeId = null,
                    cohort = null,
                    effectiveFrom = null,
                    effectiveTo = null,
                    expectedCurrentVersion = 1,
                    reason = "Missing required field should fail",
                    actorId = UUID.randomUUID(),
                    actorIp = null,
                    correlationId = UUID.randomUUID(),
                )
            }
        Assertions.assertEquals("VALIDATION_FAILED", ex.code)
        Assertions.assertFalse(ex.details.isEmpty(), "expected details[] to list the missing field")
    }

    @Test
    fun `rollback throws VERSION_NOT_FOUND when target version is unknown`() {
        val doc = newDocument(version = 5)
        whenever(documentRepository.lockByKey("pricing.base_fare")).thenReturn(Optional.of(doc))
        whenever(versionRepository.findByDocumentAndVersion(doc.id, 99L)).thenReturn(Optional.empty())
        val ex =
            Assertions.assertThrows(ApiException::class.java) {
                service.rollback(
                    key = "pricing.base_fare",
                    toVersion = 99L,
                    reason = "Rollback to a non-existent version",
                    actorId = UUID.randomUUID(),
                    actorIp = null,
                    correlationId = UUID.randomUUID(),
                )
            }
        Assertions.assertEquals("VERSION_NOT_FOUND", ex.code)
    }

    private fun newDocument(version: Long = 1): Document {
        val now = Instant.now()
        return Document(
            id = UUID.randomUUID(),
            key = "pricing.base_fare",
            tenantId = "global",
            currentVersion = version,
            schemaId = UUID.randomUUID(),
            value = """{"amount_minor":250,"currency":"EUR"}""",
            valueType = "object",
            deactivatedAt = null,
            createdAt = now,
            updatedAt = now,
            createdBy = UUID.randomUUID(),
            updatedBy = UUID.randomUUID(),
        )
    }
}
