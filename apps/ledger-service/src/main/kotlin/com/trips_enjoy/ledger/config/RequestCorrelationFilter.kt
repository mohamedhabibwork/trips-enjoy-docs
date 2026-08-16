package com.trips_enjoy.ledger.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * ADR-0019 — request-id-at-the-edge. The gateway is the canonical root
 * generator; this filter accepts `X-Request-Id` / `X-Correlation-Id` and
 * echoes them back, generating a fallback id when neither is present. The
 * value is exposed on the request attribute `correlationId` so other
 * components (ApiExceptionHandler, application services, MDC) can read it.
 */
@Component
class RequestCorrelationFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val value = request.getHeader("X-Request-Id")
            ?: request.getHeader("X-Correlation-Id")
            ?: UUID.randomUUID().toString()
        request.setAttribute("correlationId", value)
        response.setHeader("X-Request-Id", value)
        response.setHeader("X-Correlation-Id", value)
        chain.doFilter(request, response)
    }
}
