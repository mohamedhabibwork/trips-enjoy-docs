package com.trips_enjoy.configuration.application

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class SchemaValidationServiceTest {
    private val mapper = ObjectMapper()
    private val service = SchemaValidationService(mapper)

    @Test
    fun `valid integer value passes a typed schema`() {
        val schema = """{"type":"integer","minimum":1,"maximum":100}"""
        val value = mapper.readTree("42")
        val errors = service.validate(schema, value)
        Assertions.assertTrue(errors.isEmpty(), "expected no errors, got $errors")
    }

    @Test
    fun `out-of-range value produces a validation failure`() {
        val schema = """{"type":"integer","minimum":1,"maximum":100}"""
        val value = mapper.readTree("250")
        val errors = service.validate(schema, value)
        Assertions.assertFalse(errors.isEmpty(), "expected validation failure")
    }

    @Test
    fun `object schema with required fields catches missing keys`() {
        val schema = """{"type":"object","properties":{"amount_minor":{"type":"integer"},"currency":{"type":"string","minLength":3,"maxLength":3}},"required":["amount_minor","currency"]}"""
        val value = mapper.readTree("""{"amount_minor":500}""")
        val errors = service.validate(schema, value)
        Assertions.assertFalse(errors.isEmpty(), "expected missing-currency failure")
    }

    @Test
    fun `well-formed object value passes`() {
        val schema = """{"type":"object","properties":{"amount_minor":{"type":"integer"},"currency":{"type":"string","minLength":3,"maxLength":3}},"required":["amount_minor","currency"]}"""
        val value = mapper.readTree("""{"amount_minor":500,"currency":"EUR"}""")
        val errors = service.validate(schema, value)
        Assertions.assertTrue(errors.isEmpty(), "expected no errors, got $errors")
    }

    @Test
    fun `cached schema is reused on repeated validation`() {
        val schema = """{"type":"boolean"}"""
        val value = mapper.readTree("true")
        // Call many times; the cache should keep the build cost amortised.
        repeat(50) {
            Assertions.assertTrue(service.validate(schema, value).isEmpty())
        }
    }
}
