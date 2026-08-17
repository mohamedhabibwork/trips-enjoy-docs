package com.trips_enjoy.customer.api

import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.mock.web.MockHttpServletRequest

class ApiExceptionHandlerTest {
    private val handler = ApiExceptionHandler()

    @Test
    fun `api surfaces the supplier code and status`() {
        val exception = ApiException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Customer not found")
        val request = buildRequest("/v1/customers/abc")
        val result = handler.api(exception, request)
        Assertions.assertEquals(HttpStatus.NOT_FOUND.value(), result.status)
        Assertions.assertEquals("CUSTOMER_NOT_FOUND", result.properties!!.getValue("code"))
        Assertions.assertEquals("Customer not found", result.detail)
    }

    @Test
    fun `api includes details when present`() {
        val exception = ApiException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "VALIDATION_FAILED",
            "value is invalid",
            listOf(mapOf("field" to "name", "message" to "must be present")),
        )
        val request = buildRequest("/v1/customers")
        val result = handler.api(exception, request)
        @Suppress("UNCHECKED_CAST")
        val details = result.properties!!.getValue("details") as List<Map<String, String>>
        Assertions.assertEquals(1, details.size)
        Assertions.assertEquals("name", details.first()["field"])
    }

    @Test
    fun `illegal arguments are mapped to 400 VALIDATION_FAILED`() {
        val request = buildRequest("/v1/customers")
        val result: ProblemDetail = handler.illegal(IllegalArgumentException("nope"), request)
        Assertions.assertEquals(HttpStatus.BAD_REQUEST.value(), result.status)
        Assertions.assertEquals("VALIDATION_FAILED", result.properties!!.getValue("code"))
        Assertions.assertEquals("nope", result.detail)
    }

    private fun buildRequest(uri: String): HttpServletRequest {
        val request: HttpServletRequest = MockHttpServletRequest("GET", uri)
        request.setAttribute("correlationId", "00000000-0000-0000-0000-000000000001")
        return request
    }
}
