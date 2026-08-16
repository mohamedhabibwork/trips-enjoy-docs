package com.trips_enjoy.audit.application

import com.trips_enjoy.audit.api.ApiException
import com.trips_enjoy.audit.api.LitigationHoldRequest
import com.trips_enjoy.audit.domain.LitigationHold
import com.trips_enjoy.audit.domain.LitigationHoldRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import java.time.Instant
import java.util.UUID

class LitigationHoldServiceTest {

    private val repo = mock(LitigationHoldRepository::class.java)
    private val service = LitigationHoldService(repo)
    private val actor = UUID.randomUUID()

    @Test
    fun `create persists a new hold`() {
        val request = LitigationHoldRequest(
            subject_type = "customer",
            subject_id = UUID.randomUUID(),
            reason = "Pending litigation: case 12345",
        )
        val now = Instant.now()
        val saved = LitigationHold(
            id = UUID.randomUUID(),
            subjectType = request.subject_type,
            subjectId = request.subject_id,
            reason = request.reason,
            effectiveFrom = request.effective_from,
            createdBy = actor,
            createdAt = now,
        )
        `when`(repo.save(any(LitigationHold::class.java))).thenReturn(saved)
        val response = service.create(request, actor)
        assertEquals(saved.id, response.id)
        assertEquals(actor, response.created_by)
        verify(repo).save(any(LitigationHold::class.java))
    }

    @Test
    fun `create rejects a hold with no selectors`() {
        val request = LitigationHoldRequest(reason = "missing scope")
        val ex = assertThrows(ApiException::class.java) { service.create(request, actor) }
        assertEquals("VALIDATION_FAILED", ex.code)
    }

    @Test
    fun `create accepts a tenant-scoped hold`() {
        val request = LitigationHoldRequest(
            tenant_id = "global",
            reason = "Global compliance hold",
        )
        `when`(repo.save(any(LitigationHold::class.java))).thenAnswer {
            it.arguments[0] as LitigationHold
        }
        val response = service.create(request, actor)
        assertEquals("global", response.tenant_id)
        assertEquals(null, response.subject_id)
    }
}
