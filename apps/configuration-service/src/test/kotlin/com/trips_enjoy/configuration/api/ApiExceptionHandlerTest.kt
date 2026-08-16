package com.trips_enjoy.configuration.api

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class ApiExceptionHandlerTest {
    @Test
    fun `ApiException carries status, code, and detail`() {
        val ex =
            ApiException(
                HttpStatus.NOT_FOUND,
                "CONFIG_KEY_NOT_FOUND",
                "Configuration key 'pricing.base_fare' not found",
            )
        Assertions.assertEquals(HttpStatus.NOT_FOUND, ex.status)
        Assertions.assertEquals("CONFIG_KEY_NOT_FOUND", ex.code)
        Assertions.assertEquals("Configuration key 'pricing.base_fare' not found", ex.message)
    }

    @Test
    fun `ApiException supports details list for validation failures`() {
        val details =
            listOf(
                mapOf("field" to "value.amount_minor", "message" to "must be >= 0"),
                mapOf("field" to "value.currency", "message" to "must be 3 chars"),
            )
        val ex =
            ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "VALIDATION_FAILED",
                "Value does not match schema",
                details = details,
            )
        Assertions.assertEquals(2, ex.details.size)
        Assertions.assertEquals("value.amount_minor", ex.details[0]["field"])
    }

    @Test
    fun `ApiException defaults to empty details list`() {
        val ex = ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "stale version")
        Assertions.assertTrue(ex.details.isEmpty())
    }
}
