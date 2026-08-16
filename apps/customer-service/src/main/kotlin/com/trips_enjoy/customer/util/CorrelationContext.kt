package com.trips_enjoy.customer.util

import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.Authentication
import java.util.UUID

/**
 * Helpers for resolving the standard per-request identity bundle used by
 * every controller and event publisher:
 *  - correlationId (UUID, from X-Request-Id / X-Correlation-Id / generated)
 *  - actorId        (UUID, from the JWT subject; falls back to a zero UUID)
 *  - clientIp       (String, X-Forwarded-For first hop, else remoteAddr)
 */
object CorrelationContext {
    fun correlationId(request: HttpServletRequest): UUID {
        val raw = request.getAttribute("correlationId")?.toString().orEmpty()
        return runCatching { UUID.fromString(raw) }.getOrNull() ?: UUID.randomUUID()
    }

    fun actorId(authentication: Authentication?): UUID {
        val name = authentication?.name ?: return UUID(0, 0)
        return runCatching { UUID.fromString(name) }.getOrElse { UUID(0, 0) }
    }

    fun clientIp(request: HttpServletRequest): String? =
        request
            .getHeader("X-Forwarded-For")
            ?.substringBefore(',')
            ?.trim()
            ?.ifEmpty { null }
            ?: request.remoteAddr
}
