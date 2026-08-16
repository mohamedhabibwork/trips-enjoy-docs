package com.trips_enjoy.identity.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@Component
class RequestCorrelationFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val value = request.getHeader("X-Request-Id") ?: request.getHeader("X-Correlation-Id") ?: UUID.randomUUID().toString()
        request.setAttribute("correlationId", value)
        response.setHeader("X-Request-Id", value)
        response.setHeader("X-Correlation-Id", value)
        chain.doFilter(request, response)
    }
}
