package com.trips_enjoy.ledger.unit

import com.trips_enjoy.ledger.api.ApiException
import com.trips_enjoy.ledger.api.ApiExceptionHandler
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail

/**
 * Unit tests for the API exception handler. Verifies the platform error
 * envelope (RFC 7807 + code + correlationId + timestamp) is emitted with
 * the correct code per SRS §13.
 */
class ApiExceptionHandlerTest {

    private val handler = ApiExceptionHandler()

    @Test
    fun `ApiException maps to ProblemDetail with code and correlationId`() {
        val request = mock(HttpServletRequest::class.java)
        `when`(request.requestURI).thenReturn("/v1/postings")
        `when`(request.getAttribute("correlationId")).thenReturn("corr-123")

        val result: ProblemDetail = handler.api(
            ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNBALANCED_POSTING", "sum debits != sum credits"),
            request,
        )

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY.value(), result.status)
        assertEquals("UNBALANCED_POSTING", result.properties?.get("code"))
        assertEquals("corr-123", result.properties?.get("correlationId"))
        assertEquals("/v1/postings", result.instance?.toString())
        assertNotNull(result.properties?.get("timestamp"))
        assertTrue(result.type.toString().endsWith("UNBALANCED_POSTING"))
    }
}
