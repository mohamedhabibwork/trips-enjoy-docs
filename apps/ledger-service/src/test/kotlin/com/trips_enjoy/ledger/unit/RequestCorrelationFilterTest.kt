package com.trips_enjoy.ledger.unit

import com.trips_enjoy.ledger.config.RequestCorrelationFilter
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/**
 * Verifies ADR-0019 — X-Request-Id / X-Correlation-Id propagation. The
 * gateway is the canonical root generator; this filter accepts an existing
 * id and falls back to a UUID when neither header is present.
 */
class RequestCorrelationFilterTest {

    private val filter = RequestCorrelationFilter()

    @Test
    fun `existing X-Request-Id is echoed back`() {
        val request = MockHttpServletRequest().apply { addHeader("X-Request-Id", "req-abc") }
        val response = MockHttpServletResponse()
        val chain = mock(FilterChain::class.java)

        filter.doFilter(request, response, chain)

        assertEquals("req-abc", response.getHeader("X-Request-Id"))
        assertEquals("req-abc", response.getHeader("X-Correlation-Id"))
        assertEquals("req-abc", request.getAttribute("correlationId"))
        verify(chain).doFilter(request, response)
    }

    @Test
    fun `X-Correlation-Id is used when X-Request-Id is missing`() {
        val request = MockHttpServletRequest().apply { addHeader("X-Correlation-Id", "corr-xyz") }
        val response = MockHttpServletResponse()
        val chain = mock(FilterChain::class.java)

        filter.doFilter(request, response, chain)

        assertEquals("corr-xyz", response.getHeader("X-Request-Id"))
        assertEquals("corr-xyz", response.getHeader("X-Correlation-Id"))
    }

    @Test
    fun `random UUID is generated when neither header is present`() {
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()
        val chain = mock(FilterChain::class.java)

        filter.doFilter(request, response, chain)

        val generated = response.getHeader("X-Request-Id")
        assertNotNull(generated)
        // best-effort: should look like a UUID (36 chars with dashes)
        assertEquals(36, generated?.length)
    }
}
