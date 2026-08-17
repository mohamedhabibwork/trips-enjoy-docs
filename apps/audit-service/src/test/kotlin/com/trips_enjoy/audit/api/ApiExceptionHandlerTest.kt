package com.trips_enjoy.audit.api

import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.core.MethodParameter
import org.springframework.validation.BeanPropertyBindingResult
import org.springframework.validation.FieldError

class ApiExceptionHandlerTest {

    private val handler = ApiExceptionHandler()
    private val request = mock(HttpServletRequest::class.java).also {
        `when`(it.requestURI).thenReturn("/v1/audit/search")
        `when`(it.getAttribute("correlationId")).thenReturn("01HZX")
    }

    @Test
    fun `api exception is mapped to problem detail with code and correlation`() {
        val response = handler.api(ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "denied"), request)
        assertEquals(HttpStatus.FORBIDDEN.value(), response.status)
        assertEquals("FORBIDDEN", response.properties!!["code"])
        assertEquals("01HZX", response.properties!!["correlationId"])
        assertNotNull(response.properties!!["timestamp"])
        assertTrue(response.type.toString().endsWith("FORBIDDEN"))
    }

    @Test
    fun `validation failures return VALIDATION_FAILED with field list`() {
        val bindingResult = BeanPropertyBindingResult(Object(), "searchRequest")
        bindingResult.addError(FieldError("searchRequest", "reason", "must not be blank"))
        val exception = MethodArgumentNotValidException(
            MethodParameter(
                ApiExceptionHandlerTest::class.java.getDeclaredMethod("dummy", ProblemDetail::class.java),
                -1,
            ),
            bindingResult,
        )
        val response = handler.invalid(exception, request)
        assertEquals("VALIDATION_FAILED", response.properties!!["code"])
        @Suppress("UNCHECKED_CAST")
        val details = response.properties!!["details"] as List<Map<String, Any?>>
        assertEquals(1, details.size)
        assertEquals("reason", details[0]["field"])
    }

    @Suppress("unused")
    fun dummy(@Suppress("UNUSED_PARAMETER") p: ProblemDetail) {}
}
