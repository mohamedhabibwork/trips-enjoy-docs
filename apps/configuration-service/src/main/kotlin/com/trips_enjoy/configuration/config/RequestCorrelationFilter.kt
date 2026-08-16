package com.trips_enjoy.configuration.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * Establishes the per-request correlation id used across logging, events,
 * and audit_log rows (CONVENTIONS.md §2).
 *
 * Priority: X-Request-Id > X-Correlation-Id > generated UUIDv7.
 * The chosen value is set as both inbound read attribute (`correlationId`)
 * and outbound response header (both names so older clients keep working).
 */
@Component
class RequestCorrelationFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val value =
            request.getHeader("X-Request-Id")
                ?: request.getHeader("X-Correlation-Id")
                ?: UUID.randomUUID().toString()
        request.setAttribute("correlationId", value)
        response.setHeader("X-Request-Id", value)
        response.setHeader("X-Correlation-Id", value)
        chain.doFilter(request, response)
    }
}
