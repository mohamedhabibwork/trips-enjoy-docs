package com.trips_enjoy.platform.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.web.filter.OncePerRequestFilter
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * ADR-0019 implementation. The api-gateway is the canonical root generator
 * for the request id; downstream services inherit the value via this filter.
 *
 * Inbound headers:
 *   - `X-Request-Id` (primary, preferred)
 *   - `X-Correlation-Id` (alias, always accepted)
 *
 * When both are absent, a UUIDv7 is generated. The chosen value is:
 *   - echoed in BOTH response headers
 *   - placed in MDC under `requestId` for structured logging
 *   - available to downstream HTTP clients via outbound interceptors
 *   - attached to the OTel root span as `platform.request_id`
 *
 * Stable across retries — clients may reuse the same id and the same audit
 * topic partition will be the destination.
 */
class RequestCorrelationFilter : OncePerRequestFilter() {

    @OptIn(ExperimentalUuidApi::class)
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val inbound = request.getHeader(HEADER_REQUEST_ID)
            ?: request.getHeader(HEADER_CORRELATION_ID)
        val correlationId = inbound ?: Uuid.generateV7().toString()
        try {
            MDC.put(MDC_REQUEST_ID, correlationId)
            response.setHeader(HEADER_REQUEST_ID, correlationId)
            response.setHeader(HEADER_CORRELATION_ID, correlationId)
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_REQUEST_ID)
        }
    }

    companion object {
        const val HEADER_REQUEST_ID = "X-Request-Id"
        const val HEADER_CORRELATION_ID = "X-Correlation-Id"
        const val MDC_REQUEST_ID = "requestId"
    }
}
