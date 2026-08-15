package com.trips_enjoy.foodorder.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestCorrelationFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val inbound = request.getHeader("X-Request-Id") ?: request.getHeader("X-Correlation-Id")
        val requestId = inbound ?: generateRequestId()
        try {
            MDC.put("request_id", requestId)
            MDC.put("correlation_id", requestId)
            response.setHeader("X-Request-Id", requestId)
            response.setHeader("X-Correlation-Id", requestId)
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove("request_id")
            MDC.remove("correlation_id")
        }
    }

    private fun generateRequestId(): String = UUID.randomUUID().toString()
}