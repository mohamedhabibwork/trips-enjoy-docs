package com.trips_enjoy.notification.application.renderer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HandlebarsRendererTest {

	private lateinit var renderer: HandlebarsRenderer

	@BeforeEach
	fun setUp() {
		renderer = HandlebarsRenderer()
	}

	@Test
	fun `simple variable substitution`() {
		val out = renderer.render(
			"Hello {{name}}, your trip {{trip_id}} is complete.",
			mapOf("name" to "Alex", "trip_id" to "trip-123"),
		)
		assertEquals("Hello Alex, your trip trip-123 is complete.", out)
	}

	@Test
	fun `conditional section renders the truthy branch`() {
		val template = "{{#if driver}}Driver {{driver_name}}{{/if}}{{#unless driver}}No driver{{/unless}}"
		val out = renderer.render(template, mapOf("driver" to true, "driver_name" to "Sara"))
		assertEquals("Driver Sara", out)
	}

	@Test
	fun `cache returns identical compiled template on repeated render`() {
		val source = "Welcome {{user}}"
		val a = renderer.render(source, mapOf("user" to "Alex"))
		val b = renderer.render(source, mapOf("user" to "Bo"))
		assertEquals("Welcome Alex", a)
		assertEquals("Welcome Bo", b)
	}

	@Test
	fun `subject rendering returns null when source is null`() {
		assertEquals(null, renderer.renderSubject(null, mapOf("user" to "x")))
	}
}