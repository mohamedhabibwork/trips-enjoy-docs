package com.trips_enjoy.configuration.util

import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import java.util.UUID

class CorrelationContextTest {
    @Test
    fun `correlationId parses a stored request attribute`() {
        val validUuid = UUID.randomUUID().toString()
        val req: HttpServletRequest =
            MockHttpServletRequest().apply {
                setAttribute("correlationId", validUuid)
            }
        val id = CorrelationContext.correlationId(req)
        Assertions.assertEquals(UUID.fromString(validUuid), id)
    }

    @Test
    fun `correlationId mints a fresh UUID when attribute is missing or invalid`() {
        val req: HttpServletRequest = MockHttpServletRequest()
        repeat(3) {
            val id = CorrelationContext.correlationId(req)
            Assertions.assertNotNull(id)
            Assertions.assertNotEquals(UUID(0, 0), id)
        }
    }

    @Test
    fun `correlationId returns a fresh UUID when attribute is malformed`() {
        val req: HttpServletRequest =
            MockHttpServletRequest().apply {
                setAttribute("correlationId", "not-a-uuid")
            }
        val id = CorrelationContext.correlationId(req)
        Assertions.assertNotNull(id)
    }

    @Test
    fun `actorId parses a valid UUID subject`() {
        val validUuid = UUID.randomUUID().toString()
        val auth: Authentication =
            UsernamePasswordAuthenticationToken(
                validUuid,
                "n/a",
            )
        val id = CorrelationContext.actorId(auth)
        Assertions.assertEquals(UUID.fromString(validUuid), id)
    }

    @Test
    fun `actorId falls back to zero UUID when subject is missing or non-UUID`() {
        val noAuth: Authentication? = null
        Assertions.assertEquals(UUID(0, 0), CorrelationContext.actorId(noAuth))
        val plain = UsernamePasswordAuthenticationToken("plain-string", "n/a")
        Assertions.assertEquals(UUID(0, 0), CorrelationContext.actorId(plain))
    }

    @Test
    fun `clientIp prefers the first X-Forwarded-For hop`() {
        val req: HttpServletRequest =
            MockHttpServletRequest().apply {
                addHeader("X-Forwarded-For", "192.0.2.1, 10.0.0.1, 10.0.0.2")
                remoteAddr = "0.0.0.0"
            }
        Assertions.assertEquals("192.0.2.1", CorrelationContext.clientIp(req))
    }

    @Test
    fun `clientIp falls back to remoteAddr when X-Forwarded-For is absent`() {
        val req: HttpServletRequest =
            MockHttpServletRequest().apply {
                remoteAddr = "10.0.0.5"
            }
        Assertions.assertEquals("10.0.0.5", CorrelationContext.clientIp(req))
    }
}
