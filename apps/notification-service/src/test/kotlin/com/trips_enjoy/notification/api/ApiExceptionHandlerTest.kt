package com.trips_enjoy.notification.api

import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.core.MethodParameter
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.validation.BeanPropertyBindingResult
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException

class ApiExceptionHandlerTest {

	private val handler = ApiExceptionHandler()
	private val request = mock(HttpServletRequest::class.java).also {
		`when`(it.requestURI).thenReturn("/v1/notifications")
		`when`(it.getAttribute("correlationId")).thenReturn("01HZX-correlation")
	}

	@Test
	fun `api exception is mapped to problem detail with code and correlation`() {
		val response = handler.api(
			ApiException(HttpStatus.NOT_FOUND, "TEMPLATE_MISSING", "no template for (name=trip.completed)"),
			request,
		)
		assertEquals(HttpStatus.NOT_FOUND.value(), response.status)
		assertEquals("TEMPLATE_MISSING", response.properties!!["code"])
		assertEquals("01HZX-correlation", response.properties!!["correlationId"])
		assertNotNull(response.properties!!["timestamp"])
		assertTrue(response.type.toString().endsWith("TEMPLATE_MISSING"))
	}

	@Test
	fun `notification service specific codes populate the envelope correctly`() {
		val cases = listOf(
			Triple(HttpStatus.NOT_FOUND, "TEMPLATE_NOT_FOUND", "Template x not found"),
			Triple(HttpStatus.UNPROCESSABLE_ENTITY, "TEMPLATE_HAS_NO_BODY_STRUCTURED", "missing body_structured"),
			Triple(HttpStatus.BAD_GATEWAY, "PROVIDER_UNAVAILABLE", "Meta Cloud 503"),
			Triple(HttpStatus.UNPROCESSABLE_ENTITY, "RENDER_MISSING_INDEX", "whatsapp_variables missing {2}"),
			Triple(HttpStatus.UNPROCESSABLE_ENTITY, "OPTED_OUT", "user unsubscribed"),
			Triple(HttpStatus.UNPROCESSABLE_ENTITY, "WINDOW_EXPIRED", "24h window expired"),
			Triple(HttpStatus.SERVICE_UNAVAILABLE, "CIRCUIT_OPEN", "channel push circuit open"),
			Triple(HttpStatus.UNPROCESSABLE_ENTITY, "NO_CONTACT", "no device, phone, or email"),
		)
		cases.forEach { (status, code, detail) ->
			val response = handler.api(ApiException(status, code, detail), request)
			assertEquals(status.value(), response.status)
			assertEquals(code, response.properties!!["code"])
			assertEquals(detail, response.detail)
			assertTrue(response.type.toString().endsWith(code))
		}
	}

	@Test
	fun `validation failures return VALIDATION_FAILED with field details`() {
		val bindingResult = BeanPropertyBindingResult(Object(), "sendRequest")
		bindingResult.addError(FieldError("sendRequest", "user_id", "must not be null"))
		bindingResult.addError(FieldError("sendRequest", "template_name", "must not be blank"))
		val exception = MethodArgumentNotValidException(
			MethodParameter(
				ApiExceptionHandlerTest::class.java.getDeclaredMethod("dummy", ProblemDetail::class.java),
				-1,
			),
			bindingResult,
		)
		val response = handler.invalid(exception, request)
		assertEquals(HttpStatus.BAD_REQUEST.value(), response.status)
		assertEquals("VALIDATION_FAILED", response.properties!!["code"])
		@Suppress("UNCHECKED_CAST")
		val details = response.properties!!["details"] as List<Map<String, Any?>>
		assertEquals(2, details.size)
		assertEquals("user_id", details[0]["field"])
		assertEquals("template_name", details[1]["field"])
	}

	@Suppress("unused")
	fun dummy(@Suppress("UNUSED_PARAMETER") p: ProblemDetail) {}
}