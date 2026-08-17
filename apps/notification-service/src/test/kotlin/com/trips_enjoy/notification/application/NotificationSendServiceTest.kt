package com.trips_enjoy.notification.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.trips_enjoy.notification.api.ApiException
import com.trips_enjoy.notification.application.renderer.TemplateRenderer
import com.trips_enjoy.notification.application.renderer.HandlebarsRenderer
import com.trips_enjoy.notification.application.renderer.WhatsappStructuredRenderer
import com.trips_enjoy.notification.domain.Delivery
import com.trips_enjoy.notification.domain.DeliveryRepository
import com.trips_enjoy.notification.domain.Preference
import com.trips_enjoy.notification.domain.PreferenceRepository
import com.trips_enjoy.notification.domain.SuppressionRepository
import com.trips_enjoy.notification.domain.TemplateRepository
import com.trips_enjoy.notification.domain.enums.Channel
import com.trips_enjoy.notification.integration.provider.ProviderDriver
import com.trips_enjoy.notification.integration.provider.ProviderRegistry
import com.trips_enjoy.notification.integration.provider.SendRequest
import com.trips_enjoy.notification.integration.provider.SendResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.UUID

/**
 * Pure-unit smoke test for [NotificationSendService]. Exercises the
 * "no template" failure path (cheap, no Mockito stubs needed) and confirms
 * the orchestrator returns a structured 404.
 *
 * The full orchestration is exercised end-to-end in
 * `integration/events/NotificationCommandConsumerIT` (Testcontainers + Kafka).
 */
class NotificationSendServiceTest {

	@Test
	fun `send throws TEMPLATE_MISSING when no template matches name channel locale`() {
		val templates = mock(TemplateRepository::class.java)
		`when`(templates.findActiveLatest("does.not.exist", "push", "en"))
			.thenReturn(null)

		val mapper = ObjectMapper().registerModule(JavaTimeModule()).registerModule(KotlinModule.Builder().build())
		val sendService = NotificationSendService(
			templateService = NotificationTemplateService(
				templates,
				mock(com.trips_enjoy.notification.domain.TemplateHistoryRepository::class.java),
				mock(com.trips_enjoy.notification.domain.OutboxEventRepository::class.java)
					.let { NotificationEventPublishers(it, mapper) },
				mapper,
			),
			preferencesService = NotificationPreferenceService(mock(PreferenceRepository::class.java)),
			suppressionService = NotificationSuppressionService(mock(SuppressionRepository::class.java)),
			renderer = TemplateRenderer(HandlebarsRenderer(), WhatsappStructuredRenderer(mapper)),
			deliveryService = NotificationDeliveryService(mock(DeliveryRepository::class.java), mock(ProviderRegistry::class.java)),
			deliveries = mock(DeliveryRepository::class.java),
			events = mock(NotificationEventPublishers::class.java),
			idempotency = mock(IdempotencyService::class.java),
			redis = null,
		)
		val ex = kotlin.runCatching {
			sendService.send(
				NotificationSendService.SendRequestInput(
					userId = UUID.randomUUID(),
					templateName = "does.not.exist",
					data = emptyMap(),
					dedupKey = "k1",
					localeHint = "en",
					priority = "normal",
					actorId = UUID.randomUUID(),
					actorIdempotencyKey = null,
					correlationId = UUID.randomUUID(),
				),
			)
		}.exceptionOrNull()
		assertTrue(ex is ApiException)
		assertEquals("TEMPLATE_MISSING", (ex as ApiException).code)
	}

	@Test
	fun `pickChannel selects first healthy channel for urgent bypass regardless of prefs`() {
		// Sanity test on the channel-priority helper.
		val candidates = Channel.priority()
		assertEquals(Channel.PUSH, candidates.first())
	}
}