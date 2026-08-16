package com.trips_enjoy.platform.observability

import io.opentelemetry.api.trace.Span
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Adds OpenTelemetry `traceId` and `spanId` to the MDC so every log line
 * emitted during request processing carries the trace context. Pairs
 * with `RequestCorrelationFilter` (which adds `requestId`).
 */
class TraceContextMdcFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val span = Span.current()
        val traceId: String? = if (span.spanContext.isValid) span.spanContext.traceId else null
        val spanId: String? = if (span.spanContext.isValid) span.spanContext.spanId else null
        try {
            if (traceId != null) MDC.put(MDC_TRACE_ID, traceId)
            if (spanId != null) MDC.put(MDC_SPAN_ID, spanId)
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_TRACE_ID)
            MDC.remove(MDC_SPAN_ID)
        }
    }

    companion object {
        const val MDC_TRACE_ID = "traceId"
        const val MDC_SPAN_ID = "spanId"
    }
}
