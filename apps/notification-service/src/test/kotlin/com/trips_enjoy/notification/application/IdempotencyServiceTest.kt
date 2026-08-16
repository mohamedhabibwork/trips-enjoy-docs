package com.trips_enjoy.notification.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.trips_enjoy.notification.api.ApiException
import com.trips_enjoy.notification.domain.IdempotencyRecord
import com.trips_enjoy.notification.domain.IdempotencyRecordRepository
import com.trips_enjoy.notification.util.uuidV7
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.any
import org.mockito.Mockito.never
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class IdempotencyServiceTest {

	private lateinit var records: IdempotencyRecordRepository
	private lateinit var mapper: ObjectMapper
	private lateinit var service: IdempotencyService

	@BeforeEach
	fun setUp() {
		records = mock(IdempotencyRecordRepository::class.java)
		mapper = ObjectMapper()
			.registerModule(JavaTimeModule())
			.registerModule(KotlinModule.Builder().build())
		service = IdempotencyService(records, mapper)
	}

	@Test
	fun `idempotent returns stored response on second call with matching hash`() {
		val actor = uuidV7()
		val key = uuidV7()
		val request = mapOf("user_id" to uuidV7().toString(), "template_name" to "trip.completed")
		val storedBody = """{"foo":"bar"}"""
		val stored = IdempotencyRecord(
			id = uuidV7(),
			actorId = actor,
			idempotencyKey = key,
			requestHash = computeHash(mapper, request),
			responseStatus = 200,
			responseBody = storedBody,
			expiresAt = Instant.now().plus(24, ChronoUnit.HOURS),
		)
		`when`(records.findByActorIdAndIdempotencyKey(actor, key)).thenReturn(stored)
		val seen = mutableListOf<IdempotencyRecord>()
		org.mockito.Mockito.`when`(records.save(any())).thenAnswer { invocation ->
			seen.add(invocation.arguments[0] as IdempotencyRecord)
			invocation.arguments[0] as IdempotencyRecord
		}

		val first = service.idempotent(actor, key, request, Map::class.java) { mapOf("foo" to "bar") }
		val second = service.idempotent(actor, key, request, Map::class.java) { mapOf("foo" to "DIFFERENT") }
		assertEquals(mapOf("foo" to "bar"), first)
		assertEquals(mapOf("foo" to "bar"), second)
		verify(records, never()).save(any())
	}

	@Test
	fun `idempotent rejects different request body with 422 IDEMPOTENCY_KEY_REUSED`() {
		val actor = uuidV7()
		val key = uuidV7()
		val storedBody = """{"foo":"bar"}"""
		val stored = IdempotencyRecord(
			id = uuidV7(),
			actorId = actor,
			idempotencyKey = key,
			requestHash = "deadbeef-not-matching",
			responseStatus = 200,
			responseBody = storedBody,
			expiresAt = Instant.now().plus(24, ChronoUnit.HOURS),
		)
		`when`(records.findByActorIdAndIdempotencyKey(actor, key)).thenReturn(stored)

		val exception = assertThrows(ApiException::class.java) {
			service.idempotent(actor, key, mapOf("user_id" to "x"), Map::class.java) { mapOf("foo" to "bar") }
		}
		assertEquals("IDEMPOTENCY_KEY_REUSED", exception.code)
		assertEquals(422, exception.status.value())
	}

	@Test
	fun `idempotent persists response on first call with 24h ttl`() {
		val actor = uuidV7()
		val key = uuidV7()
		`when`(records.findByActorIdAndIdempotencyKey(actor, key)).thenReturn(null)
		val captor = ArgumentCaptor.forClass(IdempotencyRecord::class.java)
		`when`(records.save(captor.capture())).thenAnswer { captor.value }

		val response = service.idempotent(actor, key, mapOf("user_id" to "x"), Map::class.java) { mapOf("foo" to "bar") }
		assertEquals(mapOf("foo" to "bar"), response)
		val saved = captor.value
		assertEquals(actor, saved.actorId)
		assertEquals(key, saved.idempotencyKey)
		assertEquals(200, saved.responseStatus)
		// 24h TTL on API_STANDARDS.md §9 — accepted as ≥ 23h to absorb sub-second
		// truncation (constructed `createdAt` then `expiresAt = now + 24h` may round to 23.99h).
		val ttlHours = java.time.Duration.between(saved.createdAt, saved.expiresAt).toHours()
		assertEquals(true, ttlHours in 23L..24L, "ttlHours=$ttlHours not in [23,24]")
	}

	private fun computeHash(mapper: ObjectMapper, request: Any): String =
		java.security.MessageDigest.getInstance("SHA-256")
			.digest(mapper.writeValueAsString(request).toByteArray(Charsets.UTF_8))
			.joinToString("") { "%02x".format(it) }
}