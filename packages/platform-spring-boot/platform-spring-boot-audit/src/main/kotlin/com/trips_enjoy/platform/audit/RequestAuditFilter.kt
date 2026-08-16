package com.trips_enjoy.platform.audit

import com.trips_enjoy.platform.web.RequestCorrelationFilter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Instant
import java.util.UUID

/**
 * Emits `audit.api.request.v1` for every authenticated request. Disabled
 * by default to preserve the pre-starter behavior; consumers opt in with
 * `platform.audit.api.enabled=true`.
 */
class RequestAuditFilter(
    private val publisher: AuditEventPublisher,
    private val serviceName: String,
    private val enabled: Boolean = false,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (!enabled) {
            filterChain.doFilter(request, response)
            return
        }
        val start = System.currentTimeMillis()
        try {
            filterChain.doFilter(request, response)
        } finally {
            val durationMs = System.currentTimeMillis() - start
            val auth = SecurityContextHolder.getContext().authentication
            val actorId = (auth as? JwtAuthenticationToken)?.token?.subject
            val actorUsername = auth?.name
            val event = AuditEvent(
                auditId = UUID.randomUUID(),
                timestamp = Instant.now(),
                actorId = actorId,
                actorUsername = actorUsername,
                actorType = "user",
                service = serviceName,
                endpoint = request.method + " " + request.requestURI,
                targetResource = request.requestURI,
                result = response.status.toString(),
                durationMs = durationMs,
                requestId = response.getHeader(RequestCorrelationFilter.HEADER_REQUEST_ID),
                clientIp = request.remoteAddr,
                userAgent = request.getHeader("User-Agent"),
            )
            publisher.publish(event, "audit.api.request.v1")
        }
    }
}
