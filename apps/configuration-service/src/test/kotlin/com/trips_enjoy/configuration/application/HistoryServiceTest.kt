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
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.data.domain.PageRequest
import java.time.Instant
import java.util.Optional
import java.util.UUID

class HistoryServiceTest {
    private val documentRepository: DocumentRepository = mock()
    private val versionRepository: ConfigurationVersionRepository = mock()
    private val mapper = ObjectMapper()
    private val service = HistoryService(documentRepository, versionRepository, mapper)

    @Test
    fun `history validates the limit is in 1 to 100`() {
        val ex =
            Assertions.assertThrows(ApiException::class.java) {
                service.history("pricing.base_fare", limit = 0, cursor = null)
            }
        Assertions.assertEquals("VALIDATION_FAILED", ex.code)
        Assertions.assertTrue(ex.message!!.contains("limit"))
    }

    @Test
    fun `history throws CONFIG_KEY_NOT_FOUND when the key is missing`() {
        whenever(documentRepository.findByKey("missing.key")).thenReturn(Optional.empty())
        val ex =
            Assertions.assertThrows(ApiException::class.java) {
                service.history("missing.key", limit = 20, cursor = null)
            }
        Assertions.assertEquals("CONFIG_KEY_NOT_FOUND", ex.code)
    }

    @Test
    fun `history returns the requested page and signals has_more when more rows exist (limit+1 returned)`() {
        val doc = newDocument()
        whenever(documentRepository.findByKey(doc.key)).thenReturn(Optional.of(doc))
        // FindTop20 returns 21 rows (limit+1) to signal has_more.
        val rows = (1..21).map { newVersion(doc.id, version = it.toLong()) }
        whenever(versionRepository.findAllByDocument(doc.id, PageRequest.of(0, 21))).thenReturn(rows)
        val result = service.history(doc.key, limit = 20, cursor = null)
        Assertions.assertEquals(20, result.items.size)
        Assertions.assertTrue(result.hasMore)
        Assertions.assertNotNull(result.nextCursor)
    }

    @Test
    fun `versionAt throws VERSION_NOT_FOUND when the version is missing`() {
        val doc = newDocument()
        whenever(documentRepository.findByKey(doc.key)).thenReturn(Optional.of(doc))
        whenever(versionRepository.findByDocumentAndVersion(doc.id, 99L)).thenReturn(Optional.empty())
        val ex =
            Assertions.assertThrows(ApiException::class.java) {
                service.versionAt(doc.key, 99L)
            }
        Assertions.assertEquals("VERSION_NOT_FOUND", ex.code)
    }

    private fun newDocument(): Document {
        val now = Instant.now()
        return Document(
            id = UUID.randomUUID(),
            key = "pricing.base_fare",
            tenantId = "global",
            currentVersion = 21,
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

    private fun newVersion(
        documentId: UUID,
        version: Long,
    ): ConfigurationVersion =
        ConfigurationVersion(
            pk = ConfigurationVersionPk(id = UUID.randomUUID(), createdAt = Instant.now()),
            documentId = documentId,
            version = version,
            value = """{"amount_minor":250,"currency":"EUR"}""",
            scopeType = "global",
            reason = "test",
            correlationId = UUID.randomUUID(),
            actorId = UUID.randomUUID(),
        )
}
